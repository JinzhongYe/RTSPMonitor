# 依赖与分发材料

当前锁定：AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.10 / Compose BOM 2024.12.01 / Room 2.7.2 / LibVLC 3.7.7 / FFmpeg n8.0.1 / NDK 27.0.12077973。选用已成功构建的稳定版本，未自动追逐最新主版本。lint 提示部分依赖有更新；正式分发前应复核维护分支、安全修复与兼容性。

- [Android FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：网络外部设备连接采用 connectedDevice，需满足权限前提。
- [Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15)：dataSync/mediaProcessing 受时限约束，不适合用来承载无限连续录像。
- [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk)：本轮以[官方仓库](https://dl.google.com/android/repository/repository2-3.xml)中的稳定渠道平台 37.0 revision 2 核实版本，已将 compile/target 升至 37。安装页残留的 Preview 字样不能独立判断发布状态。平台包校验与元数据见[证据](evidence/completion/sdk37-package.xml)。
- [AGP 9.1.1 兼容性](https://developer.android.com/build/releases/agp-9-1-0-release-notes)：支持 API 37，使用 Gradle 9.3.1；已迁移内置 Kotlin，Room 暂保留官方 legacy-kapt 兼容插件，没有修改数据库结构。NDK 显式固定 27.0.12077973。
- [Android 17 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)：target 37 需要 ACCESS_LOCAL_NETWORK；本工程在用户连接或开始录像时请求，拒绝时提示，录像重连前重新检查。API 37 系统运行验收尚未执行。
- [LibVLC Android 源码](https://code.videolan.org/videolan/libvlc-android)、[VideoLAN README](https://github.com/videolan/vlc-android)：上游区分 GPL 应用与 LGPL 引擎；不能复制 GPL 应用代码后仍称闭源合规。
- [LibVLC Maven 元数据](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml)：选用 3.7.7 非 EAP；不能用 latest 字段自动选择 4.0 EAP。
- [FFmpeg 官方源码](https://github.com/FFmpeg/FFmpeg/tree/n8.0.1)、[法律说明](https://ffmpeg.org/legal.html)：FFmpeg 许可证取决于具体构建。本次 configure 报告 LGPL 2.1 or later，GPL/nonfree/version3 均关闭，没有启用编码器。后续为 H.264/AAC 流信息探测启用了对应解码器，写流仍采用 Stream Copy。FFmpeg 法律网页在本次网络访问中超时，已检查下载源码内 LICENSE.md 与 COPYING；没有据网页标题宣告合规。

源码压缩包 SHA-256：`679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1`（官方 GitHub n8.0.1 tar.gz）。构建脚本验证该校验和；此检查不等同于发布者签名验证。

LibVLC 3.7.7 AAR SHA-256：`b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b`。Gradle 依赖版本固定，当前未启用全量依赖 verification metadata。

**此工程不是完成分发合规审计的发布包。** LibVLC AAR 内含多种第三方 native 组件，仍需核实构建配置与完整对应源码，收齐通知、许可证与替换材料。FFmpeg 静态链接进 JNI 动态库，闭源分发需要提供足够的对应源码、构建脚本及满足重链接要求的目标文件或其他适用材料。仓库保留 LGPL 文本与构建源码，不能以此替代这些义务。AndroidX/Kotlin 的 Apache 2.0、Coroutines 等组件也需列入最终 notices。

正式发布前记录所有二进制来源、维护状态、已知漏洞与许可证义务；未完成项属于发布阻塞，不属于技术验证通过项。

本地测试另用 MediaMTX v1.21.3（MIT，未打包进 App），官方发布元数据与实际下载校验和见第二阶段报告；测试用主机 FFmpeg 也未打包。

## v0.5.0 检测依赖

新增固定版本 `com.google.mediapipe:tasks-vision:1.2.2`，使用 CPU 在设备本地处理帧。[官方 Android 接口](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android)。EfficientDet-Lite0 int8 v1 模型随 APK 打包，来源见 [模型说明](EFFICIENTDET_MODEL.md)，SHA-256 和 Apache-2.0 许可证随模型一起保存到应用 assets，运行时校验模型文件。此项不替代上文列出的完整分发材料审计。
