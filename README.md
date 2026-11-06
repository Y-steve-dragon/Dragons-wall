# 阿龙的墙（PrivacyScope Local）

一个**完全在本机运行**的安卓隐私观测与收敛工具：看得见 App 在拿什么、发了哪些请求，并且能在不 root 的前提下把一部分能力关掉。

- 不联网、不上传、没有账号、没有统计 SDK。
- 判断规则全部写在安装包里，读的是系统自己的记录（`/proc`、`dumpsys appops`、`PackageManager`）。
- 不是杀毒软件，也**不冒充**任何东西：不伪造设备信息、不劫持他人流量、不规避应用的安全检测。

> 名字来历：作者宿舍外号“阿龙”，“墙”指防火墙——把不该出去的东西挡在本地。

## 它做什么

| 模块 | 做什么 | 依赖 |
|---|---|---|
| 清单 | 列出已安装 App、权限、危险权限数、内置第三方 SDK 与风险分；点开看它声明的广播/服务/内容提供者/页面 | 无 |
| 实时流 | 接管本机 DNS（VpnService），把每个域名请求按 App 归因，标出追踪器/广告域名 | 无（仅本机回环） |
| 墙（拦截） | 按域名沉洞、按 App 屏蔽域名、白名单、完全放行名单；拦截记录逐条可查 | 无 |
| 权限收敛 | 用 AppOps 关掉「设备标识 / 后台运行 / 开机自启 / 剪贴板」等能力，改前有快照、可回滚 | root 或 Shizuku 或**无线调试** |
| 无线调试通道 | 连上手机自带的 adbd（uid 2000），不需要装第三方 App | Android 11+ |
| 文件观测 | 看哪些目录/文件被新建或改动（受限模式/全盘模式两档） | 全盘模式需「所有文件访问」 |
| 体检 | 一键检查此机上的高风险组合，出人话结论 | 无 |
| 教程 | 八篇零基础教程，讲清每个按钮背后的原理 | 无 |

## 它不做什么（写在最前面，避免误解）

1. **不伪造**设备信息、位置、机型号——那会污染系统状态，属于欺骗。
2. **不审计他人**：只能看本机；不提供任何针对他人设备的采集能力。
3. **不绕检测**：不隐藏自身、不反调试；被别的 App 检测到“有 VPN”是正常结果。
4. **不是包级防火墙**：当前拦截基于域名（DNS 层）。绕过办法是 App 直接用 IP 连接，这类流量本版本不拦（真包级过滤需要用户态 TCP/IP 协议栈，见 `docs/防火墙能做到哪一步.md`）。

## 环境与构建

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 36 / build-tools 36.0.0 |
| Gradle | 8.14.3 |
| AGP / Kotlin | 8.13.2 / 2.3.20 |
| minSdk / targetSdk | 24 / 36 |

![Android CI](https://github.com/<你的用户名>/<仓库名>/actions/workflows/android.yml/badge.svg)

```bash
# local.properties（不要提交）
sdk.dir=D:/path/to/android-sdk

# 构建 + 测试（自带 Gradle Wrapper，不需要本机装 Gradle）
./gradlew testDebugUnitTest    # 21 条纯逻辑单测，不需要真机/模拟器
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # 需要 keys/ + keystore.properties（见 docs/签名与发布）
```

首次构建需要联网下载依赖（AGP、Kotlin、Kadb）。之后可加 `--offline`。

> CI（`.github/workflows/android.yml`）只做两件事：**编译通过** + **单测通过**。
> 它不产出签名包——签名是人的决定，不该由 CI 代劳。

## 权限说明（每一项都对应一个功能，没有多余项）

| 权限 | 为什么 |
|---|---|
| `QUERY_ALL_PACKAGES` | 列出已安装 App —— 这是“清单”页的前提 |
| `PACKAGE_USAGE_STATS` | 读某 App 最近被使用的时间（用户手动授权） |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **仅**用于本地 VpnService 回环与读取网络状态，不发起任何外部请求 |
| `FOREGROUND_SERVICE`(+`SPECIAL_USE`) | 观测运行时必须在通知栏常驻，否则被系统杀 |
| `POST_NOTIFICATIONS` | 显示那条常驻通知 |
| `MANAGE_EXTERNAL_STORAGE` | 全盘文件观测模式（可不开，默认受限模式） |
| `READ_MEDIA_*` | 受限模式下看相册/音频/视频目录的变化 |

> 没有任何“后台静默申请”的权限：以上每一项都对应界面上一个按钮，且用户不点就不生效。

## 隐私声明

- 没有任何网络请求代码路径指向外部服务器；DNS 观测与拦截都在本机回环完成。
- 数据存在 App 私有目录的 SQLite（`privacy.db`）里，卸载即删。
- 不收集崩溃日志、不做匿名统计。

## 目录结构

```
app/src/main/java/com/privacyscope/local/
├── core/      AppOps、Bypass、Blocker、FileWatch、Ops(预设/快照)、PkgRepo、RiskScan、RiskDb、
│              Shell、Adb、Store、TrackerDb、Scanner、Pure(纯逻辑)、Quiet(异常记录)
├── vpn/       VpnService、DNS 解析/转发/沉洞、连接归因
└── ui/        MainActivity + 七个视图 + 组件库（无 XML）
app/src/test/  21 条 JVM 单测（只测 core/Pure.kt）
解包工具/      apk-explain.mjs —— 给任意 APK 出“每个文件是干什么的”中文说明
路线②-教程/    00~09 零基础教程
docs/          设计文档与能力边界
```

## 许可证

Apache License 2.0，见 `LICENSE`。第三方依赖及其许可证见 `THIRD-PARTY.md`。
