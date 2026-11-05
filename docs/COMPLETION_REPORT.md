# Android RTSP Monitor 第一阶段完成报告

日期：2026-11-05。交付版本：0.3-spike（versionCode 3）。范围：原需求第 1～6 步的工程实现、底层能力验证及交付文档。

**工程实现与当前环境的本地验证已交付；真实设备整体验收仍未完成。** 当前只有 Android 15 arm64 模拟器和本机合成 RTSP 源，没有连接真实手机或网络摄像头。以下分别记录实现、实测和未验收项，不能据此宣告所有设备场景通过。

## 交付物

- [Android 工程入口](../settings.gradle.kts)：Gradle Kotlin DSL，`app`、`core`、`data`、`media` 四模块。
- [可安装 Debug APK](https://github.com/JinzhongYe/RTSPMonitor/releases/download/v0.3-spike/RTSPMonitor-0.3-spike-debug.apk)：用于技术验证，包含 arm64-v8a / x86_64，不是正式签名发布包。
- [架构说明](ARCHITECTURE.md)、[技术验证历史](TECHNICAL_SPIKE.md)、[测试矩阵](TEST_MATRIX.md)、[依赖与许可证材料状态](DEPENDENCIES.md)。
- [第 2～6 步媒体实测报告](STEPS_2_6_VALIDATION.md)及其[原始证据](evidence/steps-2-6/)；[本次收尾证据](evidence/completion/)包含构建、认证、通知权限拒绝与回归结果。
- [环境、APK 校验和及测试计数](evidence/completion/environment.json)。历史证据保留对应版本，不将跳过用例计作通过。

## 各阶段结果

| 阶段 | 已实现 | 实际验证与边界 |
|---|---|---|
| 1 工程搭建 | Kotlin / Compose / MVVM；Room；Keystore AES-GCM；接口隔离；动态 UUID 设备 | 工程、仪器测试 APK 构建及 lint 通过；Room/Keystore 仪器测试通过。设备连接对象整体加密，数据库不存明文 RTSP 凭据 |
| 2 RTSP 播放 | 独立 `RtspPlayerEngine`；凭据输入；TCP/UDP；连接/断开；横屏全屏；状态、分辨率和编码；请求硬解 | H.264/H.265 × TCP/UDP 实际出帧与像素变化、Surface 重绑通过；320×240 合成源信息正确。物理芯片硬解与真实摄像头兼容性未验收 |
| 3 录像 | 独立 FFmpeg/libavformat；H.264/H.265 Stream Copy；AAC 复制；其他音频提示后忽略；手动停止；默认 300 秒后下一关键帧分段；异常文件保护 | 双路实际录制 616.264 秒，各生成两个 300 秒完整段和一个约 13 秒尾段；压缩视频/AAC payload 对照一致；MP4 样本读取与解码通过。长测证据对应 0.2-spike |
| 4 后台录像 | `RecordingForegroundService`；connectedDevice；常驻通知/停止；按需 WakeLock；自动重连并创建新段；停止释放资源 | API 35 模拟器回桌面/息屏、长时分段、通知停止及断线重连通过。本次补测通知权限拒绝后继续录制、回页面停止并发布成功；真实锁屏、Doze 和厂商省电尚未验收 |
| 5 本地存储 | Movies/RTSPMonitor；设备名_时间_序号命名；私有临时文件；MediaStore pending 发布；查询/删除；空间预检；异常恢复 | 发布/删除、pending 删除保护、截断恢复、杀进程重启恢复通过；MediaStore H.264/H.265 在 App 内回放实际出帧。真实相册 App 识别、实际 ENOSPC 未验收 |
| 6 多路架构 | `CameraSessionManager`；每路连接/录像状态、player、recordingEngine、reconnectCount、lastError；独立操作；命令串行化；解码/录像配额 | 两路录像独立停止、重复启停及解码配额通过；保存设备数没有写死上限。多画面接口已预留；真实手机可承受路数、摄像头最大连接数未测量 |

录像状态区分 IDLE、PREPARING、RECORDING、FINALIZING、INTERRUPTED、FAILED；连接状态区分 DISCONNECTED、CONNECTING、CONNECTED、RECONNECTING、ERROR。录像归服务及会话管理器所有，不依赖 Activity 存活。

## 多媒体路线的实测结论

正式录像按钮使用 **LibVLC 播放 + 独立 libavformat 录像**。同设备预览加录像通常需要两个 RTSP 会话。单独录像不必启动视频解码器；实际连接上限仍须逐台摄像头测量。

LibVLC sout / record 已进行 Android 实际流试验：H.264 sout 存在首关键帧问题，record 自动容器选择存在 TS/MP4 不一致；H.265 短样本可通过，仍不足以证明该方案在两种编码、动态启停和分段上可靠。因此保留诊断入口，采用独立录像引擎。没有把“API 存在”或桌面 VLC 行为当成同会话验证通过，详见[原始对照](PHASE2_VALIDATION.md)。

原生写流循环复制压缩 packet，没有启用编码器，也没有视频转码。为获取时间戳/音频帧长，流探测阶段有限使用 H.264/AAC 解码器。AAC 复制；G.711 A-law 合成源已验证提示后丢弃。其他未覆盖音频、长 GOP、B 帧、开放 GOP、参数切换和精确音画同步仍须补测。

文件采用 fragmented MP4。正常停止完成封装后发布；进程异常退出时尽力恢复已经完成的 moof/mdat 分片，丢弃不完整尾部。不能保证断电、尚未完成 GOP 或尚在系统缓存中的数据不丢失。

## 本次补测与最终检查

| 检查 | 结果 | 证据 |
|---|---|---|
| Debug 构建、单元测试、lint | PASS；17 项 JVM 测试，0 失败；lint 无错误，保留 28 条警告（版本更新、KAPT、KTX 等） | [构建输出](evidence/completion/build-check.txt)、[机器摘要](evidence/completion/environment.json) |
| Python 媒体检查工具 | PASS；3 + 5 项自测，0 失败 | [输出](evidence/completion/python-tests.txt) |
| 认证 | PASS；播放/录像正确凭据与错误密码共 4 项；特殊字符覆盖 `+`、`@`、`#`、`&` | [仪器测试](evidence/completion/authentication.txt) |
| 合成凭据日志扫描 | 未检出原文、URL 编码或 Basic 编码凭据；范围仅本次 AVD logcat 缓冲 | [扫描摘要](evidence/completion/auth-log-scan.json) |
| 实时与本地回放回归 | PASS；两编码 × TCP/UDP，另加两编码 MediaStore 回放，共 6 项 | [输出](evidence/completion/playback.txt) |
| 通知拒绝权限 | PASS；API 35，后台/息屏至少 8 秒，页面停止，媒体覆盖期间且服务退出 | [输出](evidence/completion/notifications-denied.txt) |
| 允许通知时的停止 | PASS；API 35，通知停止收尾并发布；同类中的拒绝权限用例因条件不满足跳过 | [输出](evidence/completion/background.txt) |
| Room / Keystore / pending 与原生基础检查 | PASS；data 3 项、media 基础 3 项；media 另有 30 项因未传夹具参数跳过 | [最终构建摘要](evidence/completion/environment.json) |
| 默认五分钟长测沿用范围 | 0.2-spike 的实际长测有效；0.3-spike 未重复该 616 秒测试 | [历史时间戳](evidence/steps-2-6/long-results.json)、[原生库逐字节对照](evidence/completion/native-comparison.json) |

认证首轮测试服务器因不支持所选密码字符而未启动，导致连接拒绝；修正合成账号并增加服务器启动检查后，四项通过。这是测试夹具故障，没有据此修改应用认证代码。仅验证了 Basic 认证夹具，没有覆盖所有密码字符、Digest 或厂商私有认证。

停止后的独立检查确认服务已退出、当前 WakeLock 列表无本应用锁，安装的 APK 与交付文件校验和一致，见[资源释放检查](evidence/completion/cleanup-check.json)。

升级构建工具后显式固定 NDK 27.0.12077973，避免默认 NDK 改变 native stripping。最终 APK 中的 10 个原生库与 0.2-spike 长测 APK 逐字节相同；这支持保留原媒体结论，不代替新系统运行测试。

## SDK 与后台规则修正

当前 minSdk 29，compile/target 37。此前仅依据安装页残留的 Preview 字样保持 API 36，判断不充分。本次核对[官方 SDK 仓库](https://dl.google.com/android/repository/repository2-3.xml)，确认平台 37.0 revision 2 为稳定渠道、无预览代号；已安装并成功构建。使用[支持 API 37 的 AGP 9.1.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、Gradle 9.3.1 和内置 Kotlin，Room 保留官方 legacy-kapt 兼容插件。

Android 12 的前台启动限制、Android 13 通知权限、Android 14 服务类型及 Android 15/16 后台限制核对见[此前规则表](STEPS_2_6_VALIDATION.md#后台限制核对)。使用 connectedDevice 对应外部网络摄像头；未声明本机摄像头/麦克风类型，没有自动开机录像，也不使用受六小时时限约束的 dataSync/mediaProcessing 承载连续录像。[官方类型要求](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)

Android 17 对 target 37 的本地 TCP/UDP 强制要求 ACCESS_LOCAL_NETWORK。已声明该权限，在连接、sout 和开始录像前请求；拒绝后提示，禁止未授权预览，录像失败后不无限重试；重连前再次检查。停止及本地回放不要求该权限。[官方局域网权限说明](https://developer.android.com/privacy-and-security/local-network-permission)

**本次运行环境仍为 API 35。** API 37 的实际授权/拒绝/撤销流程尚未运行；已提供 [LocalNetworkPermissionTest](../app/src/androidTest/java/com/rtspmonitor/app/LocalNetworkPermissionTest.kt) 及[执行步骤](TEST_MATRIX.md#android-17-局域网拒绝验收)，不能将编译通过写成权限运行验收通过。

## 尚未完成的验收及后续实施顺序

| 未完成项 | 当前状态与补齐方法 |
|---|---|
| 真实摄像头 H.264/H.265、TCP/UDP、认证及硬解 | BLOCKED：未提供真实设备。先单路预览、录制并回放，保存机型/SoC、固件、编码、码率、GOP 及实际解码后端 |
| 摄像头最大连接数、多路负载 | BLOCKED：没有摄像头。分别递增仅预览、仅录像、预览加录像，记录拒绝阈值；结合手机温度、CPU、内存、带宽决定配额 |
| 真机锁屏至少 30 分钟及长时稳定性 | BLOCKED：没有手机。测试回桌面、锁屏、Doze、厂商省电及 Wi-Fi 恢复，再运行至少 8 小时，核查内容覆盖与分段接缝 |
| 跨 Android 版本运行 | NOT_RUN：仅 API 35 实测。补 API 29/31/33/34/36/37；优先验证 API 37 局域网权限、FGS 启停和拒绝/撤销行为 |
| 实际存储写满、相册兼容 | NOT_RUN：空间算法已有单元测试；`/dev/full` 实际被 SELinux 拒绝（EACCES -13），不是 ENOSPC -28。应在专用设备测试真实写满，验证旧文件不丢失及相册识别 |
| 复杂码流与音画同步 | NOT_RUN：补 B 帧、开放 GOP、长 GOP、参数/分辨率切换、不同音频；发生不兼容时明确提示，保持视频不转码 |
| 正式分发材料 | 未完成：第三方二进制组成、对应源码、notices、FFmpeg 重链接材料及签名发布流程，详见[依赖文档](DEPENDENCIES.md) |

先补齐上述硬件与系统验收，再依据证据确定设备配额、是否需要调整容器/恢复策略及可支持的摄像头清单。完整多画面业务页面、设备发现和设备凭据原地编辑不在本次技术验证范围；保留验证页面，没有加入云服务器、视频转码或开机自启。

## 复现

环境：JDK 21、Android SDK Platform 37.0、Build Tools 36.0.0、NDK 27.0.12077973。首次构建下载依赖及校验后的 FFmpeg 官方源码。

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export MEDIAMTX_BIN=/absolute/path/to/mediamtx
export FFMPEG_BIN=/absolute/path/to/ffmpeg
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew :data:connectedDebugAndroidTest :media:connectedDebugAndroidTest
python3 tools/test_check_media.py
python3 tools/test_compare_video.py
./tools/run-auth-spike.sh
./tools/run-local-spike.sh playback
./tools/run-local-spike.sh notifications-denied
./tools/run-local-spike.sh background
# 其他已实施的验证入口：audio / recovery / resilience / record / udp-forwarded / long / vlc
```

本机脚本只使用合成源，默认 Android 模拟器通过 10.0.2.2 访问主机；UDP 回程使用模拟器映射。不能直接将该主机地址当作真实手机的摄像头地址。真实凭据只在 App 本地输入，不写入命令、报告或日志。
