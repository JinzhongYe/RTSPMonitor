# 第一阶段技术验证报告

本文件保留第一阶段历史结果。当前交付与 SDK 37 修正见[完成报告](COMPLETION_REPORT.md)，前一轮本地 RTSP 实测见 [第2～6步验证记录](STEPS_2_6_VALIDATION.md)，早期详细对照见 [第二阶段报告](PHASE2_VALIDATION.md)。

日期：2026-11-04。结论：工程已可构建；Android 15 模拟器基础检查通过；**尚未完成真实 RTSP、多媒体内容与手机锁屏验收**。

## 实际执行

| 检查 | 结果 | 证据 |
|---|---|---|
| Gradle Wrapper 与四模块构建 | 通过 | assembleDebug；本地 app/build/outputs/apk/debug/app-debug.apk |
| JVM 单元测试 | 3 项通过 | core/build/test-results/testDebugUnitTest |
| lintDebug | 通过，仍有版本升级等警告 | 各模块 build/reports/lint-results-debug.html |
| Python 媒体检查工具自测 | 3 项通过 | python3 tools/test_check_media.py；仅合成数据测试 |
| Android 15 模拟器安装与启动 | 通过 | RemoteHub_API_35，APK 安装及 am start |
| Keystore 加密往返及篡改拒绝 | 通过 | data connectedDebugAndroidTest，1 项 |
| Room/MediaStore 遗留 pending 与失效索引对账 | 通过 | data connectedDebugAndroidTest，1 项，Android 15 AVD |
| JNI 加载、拒绝连接及释放 | 通过 | media connectedDebugAndroidTest，1 项，127.0.0.1:1 |
| FFmpeg 原生交叉编译 | 通过 | NDK 27，arm64-v8a/x86_64；GPL=0、NONFREE=0、VERSION3=0 |
| 原生 ELF LOAD 对齐 | 静态检查通过 | 自建库及 LibVLC arm64 检查为 0x4000；不是 16KB 设备运行测试 |
| 真实 H.264/H.265、TCP/UDP | 未执行 | 没有提供真实 RTSP 地址 |
| sout 同会话显示与录制 | 未执行 | 只有 Android API/符号检查，无实际流证据 |
| MP4 内容、关键帧、三段连续录像 | 未执行 | 没有录制样本 |
| MediaStore 发布与相册识别 | 未执行 | 实现已编译，尚无有效录像发布 |
| 真实手机锁屏、后台、断网重连 | 未执行 | 没有连接手机 |
| 摄像头最大连接数与多路负载 | 未执行 | 没有摄像头 |

## 技术选择与未解决问题

LibVLC AAR 中有 record API 和 sout 符号，不足以证明 Android 实际支持可靠 MP4 与动态分段。本阶段保留 sout 重连实验和 record API 封装。尚未实测，不能下结论说 sout 不可用，也不能宣布已通过单会话方案。独立 FFmpeg 录像是提前实现的回退候选，最终选型仍须用真实流决定。

FFmpeg 录像使用自建最小库，不使用 FFmpegKit。原生代码仅复制视频 packet，并写 fragmented MP4；未引入视频重编码。停止、时间戳、HEVC 参数、丢包和恢复仍需内容级验证。MediaExtractor 检查首视频样本与轨道，不能代替整文件解码校验；恢复部分损坏 fMP4 的可用性未确认。

已修复的构建问题：Kotlin 2.0 与依赖的 2.2 元数据不兼容，统一至 2.2.10；app 缺少 Room 编译依赖；Compose Context 强转 Activity 被 lint 拒绝，改为 LocalActivity。首轮文件名测试期望值少算一个替换字符，修正后通过。

剩余工程限制：第二阶段已用有界队列替代同步发布；启动对账代码尚未经过跨版本 MediaStore 实测；多路实测前配额只能保守设置。没有凭据原地编辑页，可删除设备后重新添加。armv7 不在当前 ABI 范围。源码与第三方材料尚不足以视为完成闭源发布合规。

## 下一阶段顺序

1. 提供 H.264/H.265 摄像头和至少一台真实手机，通过本机输入凭据，避免在工单、日志或版本库中保存密码。
2. 先完成 LibVLC sout/record 对照实验：确认实际连接数、动态开始/停止、关键帧分段和正确收尾；据此定最终录像路线。
3. 验证 FFmpeg 的真实媒体内容与恢复。如果同步发布造成断流，改为有界发布队列；根据样本修正参数变更和时间戳处理。
4. 完成 MediaStore 对账、完整内容校验和异常恢复测试，再执行跨版本锁屏及多路负载测试。
5. 完成第三方二进制组成、对应源码、替换/重链接材料与分发审查后才发布正式包。
