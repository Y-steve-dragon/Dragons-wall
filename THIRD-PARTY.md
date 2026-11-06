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
