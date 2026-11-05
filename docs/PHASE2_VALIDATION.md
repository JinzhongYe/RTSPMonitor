# 第二阶段：本地 RTSP 内容验证

2026-11-04。完成本地合成流验证和录像发布链路修正；真实摄像头与真实手机验收仍未完成。

## 环境与证据

- Android 15 / API 35，arm64 Android 模拟器 RemoteHub_API_35；不是物理手机。
- LibVLC 3.7.7，FFmpeg n8.0.1 / libavformat 62.3.100，NDK 27。
- MediaMTX v1.21.3，仅监听本机回环地址。下载文件与官方发布元数据的 SHA-256 `bc1a0762fd18ffb413bcd81dcb9a072ac159fbd1f27e79ac6f14598aaac5ed3f` 一致后才运行。此前一次下载损坏，未运行。
- 构建与 lint 通过；7 项 JVM 测试、7 项 Python 自测通过。常规仪器测试通过时，依赖源地址的用例会跳过；下表的媒体结果来自单独显式执行。APK 指纹见 [环境记录](evidence/stage2/environment.json)。
- 测试源由 Android MediaCodec 生成：H.264 Baseline、H.265，320×240，10 fps，60 帧、6 秒，约一秒 GOP，无音频。主机 FFmpeg 循环发布原始压缩视频，不转码。
- [TCP 与并发原始结果](evidence/stage2/tcp-and-parallel.txt)、[后台结果](evidence/stage2/background.txt)、[MediaStore 结果](evidence/stage2/mediastore.txt)、[LibVLC 严格探测](evidence/stage2/vlc-probes.txt)、[首次对照失败](evidence/stage2/initial-rtsp-and-vlc.txt)、[媒体校验](evidence/stage2/media-checks.json)、[构建检查](evidence/stage2/build-check.txt)。
- 证据目录保留合成源视频和对应录像片段，不含摄像头画面或真实账号密码。

## 实际结果

| 检查 | 结果 | 范围 |
|---|---|---|
| H.264 TCP 录像 | PASS（修复后） | 至少三段，2 秒目标分段，首关键帧、Android 逐段解码、停止收尾 |
| H.265 TCP 录像 | PASS | 同上 |
| 视频无重编码 | PASS（所附样本） | 两种编码的录像各 20 个 VCL NAL 与源切片逐字节哈希一致；忽略传输/容器引入的非 VCL 参数 NAL |
| 双路独立录像 | PASS | H.264 与 H.265 同时录制；停止前者后，后者继续生成新段并可解码 |
| MediaStore | 3 项 PASS | 两种编码的发布/路径/IS_PENDING/字节一致性/删除；非法容器不发布且源文件保留 |
| 模拟器后台/息屏 | PASS | 前台启动后回桌面、息屏 8 秒，仍 RECORDING；通知停止后发布，视频覆盖至少 7 秒；服务退出 |
| LibVLC H.264 sout MP4 | FAIL | 能生成 MP4，但首样本非关键帧；首次样本还未通过源切片一致性检查 |
| LibVLC H.265 sout MP4 | PASS（短样本） | 首关键帧、Android 解码；不等于动态开始/停止及五分钟分段通过 |
| LibVLC H.264 record API | 有不一致结果 | 首次输出 TS，后续严格测试输出可解码 MP4；不能保证每次得到 MP4 |
| LibVLC H.265 record API | PASS（短样本） | 保持预览；API 查询确认停止录制前后 RTSP reader session ID 不变 |
| UDP（两种编码） | FAIL，原因待定 | 本机模拟器路径未收到可供读取的媒体 packet；RTSP SETUP 成功不代表 RTP 成功。尚未区分 NAT/模拟器限制与实现问题 |
| 默认 300 秒分段、长时录制 | NOT_RUN | 当前使用 2 秒目标加速验证，不能替代五分钟及长时测试 |
| 真机锁屏、摄像头最大连接数/温升 | NOT_RUN | 没有真实手机、摄像头证据 |

严格 LibVLC 探测最后一次为 4 项中 3 项通过、H.264 sout 1 项失败；不能把失败用例从结果中删除或称为全部通过。较早的对照还记录了 H.264 record 输出 TS。所有结论只适用于上述版本和合成源。

## 修正与路线选择

H.264 的首次 TCP 测试已连接却没有文件。临时诊断确认存在关键帧和 PTS，但 DTS 持续为 AV_NOPTS_VALUE，被原循环全部跳过。检查 FFmpeg `libavformat/demux.c` 的解码延迟判断后，为 `avformat_find_stream_info` 启用 H.264 探测解码器；相同用例由超时变为通过。原生写流循环仍只复制 packet，没有视频编码器。修改构建配置时现在会重新 configure，避免旧 config.h 静默保留旧功能集。临时诊断代码已移除。

增加流信息探测后的截止时间检查：SDP 已提供编码信息时，即使没有 RTP 数据，find_stream_info 仍可能返回部分成功；不应因此重新延长已经耗尽的探测截止时间。

分段发布改为独立有界队列，最多排队两段，另有一段可能正在发布。原生回调只提交已封装文件。队列满或发布失败时停止该路录像，保留未发布文件；正常停止等待队列收尾。单元测试覆盖慢发布、队列上限、失败停止和取消后的排空。没有测量或宣称吞吐提升。

MediaStore 发布前逐个读取视频样本，拒绝空样本、读取不完整和超过 32 MiB 的单样本，避免无限制分配内存。此检查是容器/样本结构检查，不等于在生产录像过程中完整解码视频。服务启动录像前等待恢复完成，避免扫描正在写入的文件。

当前继续采用 **LibVLC 播放 + 独立 libavformat 录像**。同设备预览并录像通常需要两个 RTSP 会话。原因是本次没有证明 LibVLC 可稳定满足两种编码、指定 MP4、原会话动态开始/停止及关键帧分段的全部条件；不能据此声称 LibVLC 完全不能录像。TS 后处理转 MP4或更严格的启动时序可作为后续单连接研究，当前未替换引擎。

## 复现与下一轮

启动 Android 模拟器，准备官方 MediaMTX 和支持 RTSP 输出的 FFmpeg 可执行文件：

```bash
export MEDIAMTX_BIN=/absolute/path/to/mediamtx
export FFMPEG_BIN=/absolute/path/to/ffmpeg
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./tools/run-local-spike.sh record
./tools/run-local-spike.sh background
# 以下是能力探测；当前环境存在已知失败，不应作为全绿构建门槛
./tools/run-local-spike.sh vlc
./tools/run-local-spike.sh udp
```

脚本默认 emulator-5554；可用 ANDROID_SERIAL 指定另一个模拟器。源地址使用模拟器的 10.0.2.2，脚本不是物理手机测试工具。脚本生成测试源、启动回环 RTSP 服务、执行测试并保存文件到 tools/vendor/spike-*；结束时关闭自己启动的服务。record 模式的完整脚本已实际执行通过。background 模式对应的仪器测试已实际执行通过，脚本分支仅做语法检查。

```bash
# ffprobe 位于 PATH
python3 tools/check-media.py docs/evidence/stage2/h264-segment.mp4
python3 tools/compare-video.py docs/evidence/stage2/h264-source.mp4 docs/evidence/stage2/h264-segment.mp4
python3 tools/test_check_media.py
python3 tools/test_compare_video.py
./gradlew assembleDebug testDebugUnitTest lintDebug
```

下一轮先在真实局域网排查 UDP，验证 B 帧、AAC 与不兼容音频、断线重连、杀进程恢复和存储写满，再执行默认五分钟分段与真机至少 30 分钟锁屏。摄像头并发连接上限、热量、耗电与后台厂商限制必须单独测，不能从本次两路合成流外推。正式分发前的原生依赖源码/重链接材料与许可证审查仍未完成。
