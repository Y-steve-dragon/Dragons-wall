# 第三方依赖清单（THIRD-PARTY）

本工程只允许 **Apache-2.0 / MIT** 类宽松许可证。**禁止**引入 GPL / AGPL 代码（会传染整个 App）。

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| androidx.core:core-ktx | 1.13.1 | Apache-2.0 | 基础扩展 |
| androidx.appcompat:appcompat | 1.7.0 | Apache-2.0 | Activity 兼容 |
| com.google.android.material:material | 1.12.0 | Apache-2.0 | 部分控件 |
| androidx.recyclerview:recyclerview | 1.3.2 | Apache-2.0 | 清单/事件列表 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.9.0 | Apache-2.0 | 异步 |
| dev.rikka.shizuku:api / :provider | 13.1.5 | Apache-2.0 | **可选**特权通道（不装 Shizuku 也可运行） |
| com.flyfishxu:kadb-android | 2.1.1 | **Apache-2.0** | 自连无线调试（adb 协议 + SPAKE2 配对） |
| com.github.Flyfish233:spake2-java（kadb 的传递依赖，JitPack） | 1.1.1 | Apache-2.0 | SPAKE2 配对密码学 |

> Kadb 2.1.2 及以上要求 `compileSdk 37`，本工程使用 **2.1.1**（`minCompileSdk=36`）。
> 升级 Kadb 前必须同步升级 compileSdk，否则构建在 `checkDebugAarMetadata` 阶段失败。

## 数据（高危域名库）

| 来源 | 许可 | 用途 | 落地位置 |
|---|---|---|---|
| [malware-filter / urlhaus-filter](https://gitlab.com/malware-filter/urlhaus-filter)（数据源自 [abuse.ch URLhaus](https://urlhaus.abuse.ch/)） | MIT（项目）/ **CC0 1.0**（URLhaus 数据） | 恶意软件分发域名 | `app/src/main/res/raw/risk_domains.txt`（类别 1） |
| [malware-filter / phishing-filter](https://gitlab.com/malware-filter/phishing-filter) | MIT | 钓鱼站点域名 | 同上（类别 2） |

- 只做**离线快照**：生成脚本 `kb/_build-riskdb.mjs`，跑一次下载一次，App 运行时**不联网更新**。
- 命中后**只标黄**，不拦截、不外传、不上报（本 App 没有服务端）。
- 数据条数以文件头注释为准（约 2.3 万条）。
