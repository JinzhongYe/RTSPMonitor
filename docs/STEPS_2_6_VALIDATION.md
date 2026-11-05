# 第 2～6 步实施与验证记录

本文件保留 0.2-spike 的实测证据；0.3-spike 与最新验收状态见[完成报告](COMPLETION_REPORT.md)。

日期：2026-11-05。APK：0.2-spike，最低 API 29，compile/target API 36，arm64-v8a / x86_64。本轮在 Android 15 arm64 模拟器和本机 MediaMTX 合成源上完成播放、录像、后台、存储及多路会话验证；没有真实手机或摄像头证据，不宣告真实设备验收完成。

## 按原需求交付

| 步骤 | 实现 | 实际验证 |
|---|---|---|
| 2 RTSP 播放 | 独立 RtspPlayerEngine；URL/凭据、TCP/UDP、连接/断开、横屏全屏、状态、编码与尺寸 | H.264/H.265 × TCP/UDP 实际出帧；PixelCopy 确认灰色视频持续变化；显示 320×240；更换 View 后继续播放 |
| 3 录像 | 独立 libavformat；视频 Stream Copy；AAC 复制；不兼容音频提示后忽略；默认 300 秒后下一关键帧分段 | 两路实际持续约 616 秒，各生成两个完整五分钟片段和一个尾段；AAC 包数、解码、压缩视频/AAC 包哈希对照；G.711 A-law 明确提示后丢弃 |
| 4 后台 | connectedDevice 前台服务、常驻通知及停止、按需 WakeLock、断线退避重连 | 前台启动后回桌面/息屏，默认分段长测持续录像；通知停止完成发布并退出服务；主动断开 RTSP 后重连并生成新文件 |
| 5 存储 | Movies/RTSPMonitor；私有临时文件、IS_PENDING 发布、索引/查询/删除、空间保护、异常恢复 | 发布文件字节一致；pending 行无法删除；截断尾部恢复后保留全部 60 帧；杀进程后重新启动恢复有效分片；App 内 MediaStore H.264/H.265 回放实际出帧 |
| 6 多路 | 动态 UUID 会话；独立播放器/录像引擎/状态；每设备命令锁；解码与录像配额 | 两路独立录像，停止一路不影响另一路；反复停止/重启；解码配额不能被 sout 实验绕过；未把设备数量写死 |

视频输出使用 SurfaceView 与透明辅助 Surface 满足 LibVLC 不透明硬解输出的要求。尺寸来自视频布局回调，避免把早期轨道元数据的 0×0 当成有效分辨率。Surface 就绪与视频轨道重新启用分别处理，防止重绑黑屏。硬件解码已请求且允许回退；模拟器结果不能证明物理芯片的硬解能力或并发上限。

当前正式录像按钮使用 **LibVLC 播放 + 独立 FFmpeg 录像**，同摄像头预览加录像通常占两路 RTSP 会话。LibVLC 同会话试验仍保留；上一轮 H.264 sout 首关键帧、record 自动选择容器存在失败/不一致，尚不能替代此路线。详见[第二阶段历史报告](PHASE2_VALIDATION.md)。摄像头的实际最大连接数尚未测量。

## 本轮修复及证据

- **AAC 丢包**：旧最小构建每两秒只留下约 6～7 个 AAC 包。回归断言先失败；启用 AAC 探测解码器补齐 frame_size/时间戳推导后，完整片段包数与解码通过。只在流探测中有限解码，写流循环复制 packet，未启用视频或音频编码器。
- **进程退出丢失缓冲**：首次杀进程后临时文件仅有 28 字节 ftyp。增加 AVFMT_FLAG_FLUSH_PACKETS，使已经完成的 fMP4 分片离开 AVIO 缓冲；相同流程恢复出可读文件。只保证尽力恢复完整分片，不保证断电或尚未完成 GOP 的数据不丢失。
- **截断文件发布**：增加顶层 MP4 box 边界检查；普通不完整 MP4 拒绝发布，私有 .part 可裁去最后未完成分片，保留完整 moof/mdat 前缀，再读取全部视频样本验证。此检查不代替完整视频解码。
- **本地回放失败**：直接传 content:// 给 LibVLC 位置接口失败。改用 ContentResolver 打开文件描述符，直到停止/切换输入才关闭；本地视频重绑时恢复解码位置。回放引擎和 URI 归 ViewModel 管理，Activity 重建时保留。
- **多路操作竞争**：每设备的开始、停止、删除通过 Mutex 串行处理，仍在收尾的任务继续占用录像配额。服务只保留一个退出监测任务。正在发布的 MediaStore 行通过 IS_PENDING=0 条件保护删除操作。
- **写入失败**：原生封装/写入发生文件系统错误时单独报告，不把此类错误无限当成网络故障重连；写坏的片段保留在临时目录，不发布。空间预检保留 128 MiB，并计算发布时的第二份副本空间。

[证据目录](evidence/steps-2-6/)保存测试输出、长测时间戳、媒体检查、合成源和样本。核心结果：17 项 JVM 测试、8 项 Python 自测；解码/回放、AAC/音频丢弃、容器恢复、MediaStore、命令串行化、通知停止、长时分段均有显式仪器测试。依赖源地址或长测参数的用例默认跳过，跳过不计作通过。进程恢复测试中的准备进程被主机故意终止，日志中的 Process crashed 是预期注入；后续恢复用例必须另行通过。

长测两路的前两个片段末视频 PTS 均为 299900000 微秒；以 10 fps 计为完整 300 秒段，另有约 13 秒尾段。完整结果见 [长测时间戳](evidence/steps-2-6/long-results.json)和[媒体校验](evidence/steps-2-6/media-checks.json)，不用短时加速测试冒充五分钟实测。

写满测试受环境限制：/dev/full 被 Android SELinux 拒绝，实际返回 EACCES（-13），已验证文件系统失败上报及不发布保护。**没有完成真正 ENOSPC（-28）或物理存储写满测试**；空间边界由单元测试覆盖。未关闭 SELinux，也未填满共享磁盘。

## 后台限制核对

以下是官方规则核对，不等于在所有 Android 版本上跑过实机测试：

| 系统 | 规则与处理 |
|---|---|
| Android 12 | 从用户可见 Activity 操作启动 FGS，拒绝时提示；没有后台自动开始/开机录制。[官方启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) |
| Android 13 | 声明并请求 POST_NOTIFICATIONS；本轮后续已在 API 35 模拟器补测拒绝权限后录像与页面停止，API 33 实机仍待验证，不能保证通知栏停止按钮可见。[官方通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission) |
| Android 14 | 声明 connectedDevice 及对应 FGS 权限，按网络外部摄像头场景满足 CHANGE_NETWORK_STATE 前提；不声明本机 CAMERA/MICROPHONE 类型。[官方类型要求](https://developer.android.com/develop/background-work/services/fgs/service-types) |
| Android 15 | 不使用受六小时额度限制的 dataSync/mediaProcessing 承载连续录像；本轮实际运行 API 35。[官方行为变化](https://developer.android.com/about/versions/15/behavior-changes-15) |
| Android 16 | 录像不依赖 JobScheduler/WorkManager，不能把 FGS 当作 job 配额豁免；API 36 运行仍待验证。[官方配额变化](https://developer.android.com/about/versions/16/behavior-changes-all) |
| 更新版本 | 此轮曾依据安装页 Preview 字样保持 API 36；后续核对官方仓库确认平台 37.0 已在稳定渠道，0.3-spike 已升级并加入局域网授权。详见完成报告。[官方 SDK 页面](https://developer.android.com/about/versions/17/setup-sdk) |

UDP 直接回传到本机模拟器曾失败。使用 Android Emulator 支持的 UDP redir，为 SETUP 协商的 client_port 建立回程映射后，应用保持 UDP、两种编码均通过；没有偷偷切换为 TCP。测试控制代理只在回环地址监听，仅转发 RTSP 控制消息，RTP/RTCP 仍走 UDP，不随 APK 分发。[模拟器网络说明](https://developer.android.com/studio/run/emulator-networking-interconnect)

## 复现与验收边界

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export MEDIAMTX_BIN=/absolute/path/to/mediamtx
export FFMPEG_BIN=/absolute/path/to/ffmpeg
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 tools/test_check_media.py
python3 tools/test_compare_video.py
./tools/run-local-spike.sh playback       # 实时与 MediaStore 文件回放
./tools/run-local-spike.sh audio          # AAC 与 A-law 策略
./tools/run-local-spike.sh recovery       # 截断容器、写入失败保护
./tools/run-local-spike.sh resilience     # 断线重连、杀进程恢复
./tools/run-local-spike.sh long           # 真实运行约十分钟，两路默认300秒分段
```

playback/audio/recovery/resilience 包装脚本均已完整执行；long 包装分支做过语法检查，底层同一个 LongRecordingTest 已显式执行两轮。默认串口 emulator-5554，可用 ANDROID_SERIAL 指定另一个模拟器。主机 FFmpeg 需支持 WAV/RTSP/MP4，所有源均为合成测试数据。脚本结束会关闭自己启动的进程和端口映射。

本轮没有开发完整业务页面或生产发布包，保留技术验证页面与诊断入口。

尚未验收：真实手机至少 30 分钟锁屏、Doze/厂商省电策略、摄像头最大连接数、B 帧/开放 GOP/参数切换、精确音画同步、真实磁盘写满、其他 Android 版本运行、温升与功耗。不能从两路低码率合成源推断手机可运行多少路。32 位 ABI、完整多画面业务页及设备发现未加入本阶段；没有云服务、自动开机录像或视频转码。正式分发前仍需补齐第三方组件审计、对应源码和重链接材料。
