package com.privacyscope.local.core

/**
 * 权限名翻译（英文 → 中文人话）。
 *
 * 两个来源：
 *  1) 常用权限直接查表（M）—— 这些是「能看你东西」的那些，说法固定，必须准确；
 *  2) 表里没有的，按词根拼（WORDS）—— 安卓有几百个权限，各家 SDK 还会自定义
 *     （例如 `com.huawei.permission.X`），不可能全收，但词根能覆盖绝大多数组合，
 *     例如 `READ_MEDIA_VISUAL_USER_SELECTED` → 「读 媒体 视觉 用户 已选」。
 *
 * 规矩：**猜不出来就照实说**，返回「未收录：原名」，绝不编一个看着像的解释。
 */
object PermZh {

    /** 常用权限 → 中文（也是「危险权限」的判定依据，改这里会影响清单页的红色标记） */
    val M: Map<String, String> = mapOf(
        // 短信 / 通话
        "android.permission.READ_SMS" to "读短信",
        "android.permission.RECEIVE_SMS" to "收短信",
        "android.permission.SEND_SMS" to "发短信",
        "android.permission.RECEIVE_MMS" to "收彩信",
        "android.permission.RECEIVE_WAP_PUSH" to "收 WAP 推送",
        "android.permission.READ_CELL_BROADCASTS" to "读小区广播",
        "android.permission.READ_PHONE_STATE" to "读手机状态（含设备标识）",
        "android.permission.READ_PHONE_NUMBERS" to "读本机号码",
        "android.permission.CALL_PHONE" to "直接拨号",
        "android.permission.ANSWER_PHONE_CALLS" to "接听电话",
        "android.permission.PROCESS_OUTGOING_CALLS" to "监听去电",
        "android.permission.READ_CALL_LOG" to "读通话记录",
        "android.permission.WRITE_CALL_LOG" to "写通话记录",
        // 通讯录 / 日历 / 账户
        "android.permission.READ_CONTACTS" to "读通讯录",
        "android.permission.WRITE_CONTACTS" to "改通讯录",
        "android.permission.GET_ACCOUNTS" to "读账户列表",
        "android.permission.READ_CALENDAR" to "读日历",
        "android.permission.WRITE_CALENDAR" to "改日历",
        // 位置
        "android.permission.ACCESS_FINE_LOCATION" to "精确定位",
        "android.permission.ACCESS_COARSE_LOCATION" to "粗略定位",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "后台定位",
        "android.permission.ACCESS_MEDIA_LOCATION" to "图片里的位置信息",
        // 相机 / 录音 / 身体
        "android.permission.CAMERA" to "相机",
        "android.permission.RECORD_AUDIO" to "录音",
        "android.permission.BODY_SENSORS" to "身体传感器（心率等）",
        "android.permission.BODY_SENSORS_BACKGROUND" to "后台身体传感器",
        "android.permission.ACTIVITY_RECOGNITION" to "运动识别（走路/跑步）",
        "android.permission.HIGH_SAMPLING_RATE_SENSORS" to "高频传感器",
        // 存储 / 媒体
        "android.permission.READ_EXTERNAL_STORAGE" to "读外部存储",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "写外部存储",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "管理所有文件",
        "android.permission.READ_MEDIA_IMAGES" to "读图片",
        "android.permission.READ_MEDIA_VIDEO" to "读视频",
        "android.permission.READ_MEDIA_AUDIO" to "读音频",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to "读用户选中的图片/视频",
        // 应用 / 包
        "android.permission.QUERY_ALL_PACKAGES" to "读已安装应用列表",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "安装应用",
        "android.permission.REQUEST_DELETE_PACKAGES" to "卸载应用",
        "android.permission.DELETE_PACKAGES" to "删除应用包",
        "android.permission.PACKAGE_USAGE_STATS" to "看应用使用情况",
        "android.permission.PACKAGE_USAGE_STATS_APP" to "看应用使用情况",
        "android.permission.GET_TASKS" to "读正在运行的任务",
        "android.permission.REORDER_TASKS" to "调整任务顺序",
        "android.permission.KILL_BACKGROUND_PROCESSES" to "结束后台进程",
        // 界面 / 通知
        "android.permission.SYSTEM_ALERT_WINDOW" to "悬浮窗",
        "android.permission.POST_NOTIFICATIONS" to "发通知",
        "android.permission.USE_FULL_SCREEN_INTENT" to "全屏通知",
        "android.permission.FOREGROUND_SERVICE" to "前台服务",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" to "前台服务（特殊用途）",
        "android.permission.RECEIVE_BOOT_COMPLETED" to "开机自启",
        "android.permission.WAKE_LOCK" to "阻止休眠",
        "android.permission.VIBRATE" to "振动",
        "android.permission.FLASHLIGHT" to "闪光灯",
        "android.permission.SET_WALLPAPER" to "换壁纸",
        "android.permission.EXPAND_STATUS_BAR" to "展开状态栏",
        "android.permission.WRITE_SETTINGS" to "改系统设置",
        "android.permission.WRITE_SECURE_SETTINGS" to "改系统安全设置",
        "android.permission.CHANGE_CONFIGURATION" to "改系统配置",
        "android.permission.DISABLE_KEYGUARD" to "解除锁屏",
        "android.permission.BIND_DEVICE_ADMIN" to "设备管理器",
        // 网络 / 硬件
        "android.permission.INTERNET" to "联网",
        "android.permission.ACCESS_NETWORK_STATE" to "读网络状态",
        "android.permission.CHANGE_NETWORK_STATE" to "改网络状态",
        "android.permission.ACCESS_WIFI_STATE" to "读 Wi-Fi 状态",
        "android.permission.CHANGE_WIFI_STATE" to "改 Wi-Fi 状态",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE" to "Wi-Fi 组播",
        "android.permission.NEARBY_WIFI_DEVICES" to "附近的 Wi-Fi 设备",
        "android.permission.BLUETOOTH" to "蓝牙",
        "android.permission.BLUETOOTH_ADMIN" to "管理蓝牙",
        "android.permission.BLUETOOTH_CONNECT" to "连接蓝牙设备",
        "android.permission.BLUETOOTH_SCAN" to "扫描蓝牙设备",
        "android.permission.BLUETOOTH_ADVERTISE" to "蓝牙广播",
        "android.permission.NFC" to "NFC",
        "android.permission.TRANSMIT_IR" to "红外发射",
        "android.permission.USE_BIOMETRIC" to "指纹/人脸识别",
        "android.permission.USE_FINGERPRINT" to "指纹",
        "android.permission.SCHEDULE_EXACT_ALARM" to "精确闹钟",
        "android.permission.USE_EXACT_ALARM" to "精确闹钟",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "申请忽略电池优化",
        // 无障碍 / 输入 / 屏幕捕获（这三类风险最高）
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to "无障碍服务（能看你屏幕）",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to "读取所有通知",
        "android.permission.BIND_INPUT_METHOD" to "输入法（能看你打的字）",
        "android.permission.BIND_VPN_SERVICE" to "VPN 服务",
        "android.permission.MEDIA_PROJECTION" to "屏幕录制/截图",
        "android.permission.CAPTURE_AUDIO_OUTPUT" to "录制内部音频",
        "android.permission.RECORD_AUDIO_OUTPUT" to "录制音频输出",
        "android.permission.MANAGE_DEVICE_POLICY_DEVICE_IDENTIFIERS" to "读设备标识（受管设备）",
        // 各家厂商自定义的常见项
        "com.huawei.permission.ACCESS_HW_KEYSTORE" to "华为钥匙库",
        "com.hihonor.permission.ACCESS_HW_KEYSTORE" to "荣耀钥匙库",
        "com.miui.permission.READ_DEVICE_ID" to "小米读设备标识",
        "com.oppo.permission.READ_DEVICE_ID" to "OPPO 读设备标识",
        "com.vivo.permission.READ_DEVICE_ID" to "vivo 读设备标识"
    )

    /** 词根表：表里没有的权限名按这些词拼中文（拼不出来就照实说） */
    private val WORDS = mapOf(
        "READ" to "读", "WRITE" to "写", "RECEIVE" to "接收", "SEND" to "发送",
        "GET" to "获取", "SET" to "设置", "CHANGE" to "更改", "MODIFY" to "修改",
        "ACCESS" to "访问", "MANAGE" to "管理", "QUERY" to "查询", "KILL" to "结束",
        "SMS" to "短信", "MMS" to "彩信", "CALL" to "通话", "LOG" to "记录",
        "PHONE" to "手机", "NUMBER" to "号码", "NUMBERS" to "号码",
        "CONTACTS" to "通讯录", "CALENDAR" to "日历", "ACCOUNTS" to "账户",
        "CAMERA" to "相机", "AUDIO" to "音频", "RECORD" to "录制", "VIDEO" to "视频",
        "IMAGE" to "图片", "IMAGES" to "图片", "VISUAL" to "视觉", "MEDIA" to "媒体",
        "SELECTED" to "已选", "USER" to "用户", "LOCATION" to "位置",
        "FINE" to "精确", "COARSE" to "粗略", "BACKGROUND" to "后台",
        "STORAGE" to "存储", "EXTERNAL" to "外部", "INTERNAL" to "内部",
        "FILES" to "文件", "ALL" to "全部", "PACKAGES" to "应用列表",
        "PACKAGE" to "应用包", "INSTALL" to "安装", "DELETE" to "删除",
        "REQUEST" to "请求", "SYSTEM" to "系统", "ALERT" to "弹窗",
        "WINDOW" to "窗口", "OVERLAY" to "悬浮", "POST" to "发送",
        "NOTIFICATION" to "通知", "NOTIFICATIONS" to "通知",
        "FOREGROUND" to "前台", "SERVICE" to "服务", "SERVICES" to "服务",
        "BOOT" to "开机", "COMPLETED" to "完成", "WAKE" to "保持", "LOCK" to "唤醒锁",
        "VIBRATE" to "振动", "FLASHLIGHT" to "闪光灯",
        "SENSOR" to "传感器", "SENSORS" to "传感器", "BODY" to "身体",
        "ACTIVITY" to "活动", "RECOGNITION" to "识别", "SAMPLING" to "采样",
        "RATE" to "频率", "HIGH" to "高频",
        "NETWORK" to "网络", "STATE" to "状态", "INTERNET" to "联网",
        "WIFI" to "Wi-Fi", "BLUETOOTH" to "蓝牙", "SCAN" to "扫描",
        "CONNECT" to "连接", "ADVERTISE" to "广播", "NEARBY" to "附近",
        "DEVICE" to "设备", "DEVICES" to "设备", "IDENTIFIERS" to "标识",
        "SETTINGS" to "设置", "SECURE" to "安全", "USAGE" to "使用情况",
        "STATS" to "统计", "TASK" to "任务", "TASKS" to "任务",
        "PROCESS" to "进程", "PROCESSES" to "进程", "OUTGOING" to "去电",
        "BROADCAST" to "广播", "BOUNDARY" to "边界", "MULTICAST" to "组播",
        "SCREEN" to "屏幕", "CAPTURE" to "捕获", "PROJECTION" to "投射",
        "BIOMETRIC" to "生物识别", "FINGERPRINT" to "指纹", "IR" to "红外",
        "EXACT" to "精确", "ALARM" to "闹钟", "BATTERY" to "电池",
        "OPTIMIZATIONS" to "优化", "IGNORE" to "忽略", "KEYGUARD" to "锁屏",
        "DISABLE" to "禁用", "CONFIGURATION" to "配置", "STATUS" to "状态",
        "BAR" to "栏", "WALLPAPER" to "壁纸", "ADMIN" to "管理",
        "ACCESSIBILITY" to "无障碍", "LISTENER" to "监听",
        "INPUT" to "输入", "METHOD" to "方法", "VPN" to "VPN",
        "DUMP" to "转储", "BIND" to "绑定", "REORDER" to "重排",
        "MANAGE_EXTERNAL" to "管理外部", "PERMISSION" to "", "PERMISSIONS" to "",
        "RELATED" to "相关", "REAL" to "真实", "TIME" to "时间"
    )

    private val ANDROID = "android.permission."

    /** 权限名 → 中文。认不出就返回 `未收录：原名`，不猜。 */
    fun zh(p: String): String {
        M[p]?.let { return it }
        val short = p.substringAfterLast('.')
        val obj = p.startsWith(ANDROID)
        val body = if (obj) p.removePrefix(ANDROID) else short
        val parts = body.split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "未收录：$short"
        val sb = StringBuilder()
        var unknown = 0
        for (w in parts) {
            val t = WORDS[w]
            if (t == null) {
                unknown++
                // 认不出的词原样附在后面，方便你搜索
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(w)
            } else {
                sb.append(t)
            }
        }
        val s = sb.toString().trim()
        return if (s.isEmpty()) "未收录：$short" else if (unknown == parts.size) "未收录：$short" else s
    }

    /** 给界面用：中文 + 原始名（原始名放在括号里，方便对着文档核） */
    fun zhWithRaw(p: String): String {
        val z = zh(p)
        val short = p.substringAfterLast('.')
        return if (z.startsWith("未收录")) z else "$z（$short）"
    }
}
