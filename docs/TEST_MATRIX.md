# 测试矩阵

0.6.0 新增独立回放、来源与时间范围命名；测试计数、活动通知的原生环境阻塞见[本轮记录](PLAYBACK_RECORDING_UPDATE.md)。


每次记录 APK/源码版本、Android/API、机型/SoC、摄像头固件、编码/分辨率/fps/GOP/码率、音频、传输、网络和证据文件。凭据不得写入报告。状态用 PASS / FAIL / BLOCKED / NOT_RUN；没有设备证据不得用 PASS。

## 当前交付结果

当前版本 0.3-spike，compile/target 37。收尾的认证、通知权限拒绝、播放回归和构建证据见[完成报告](COMPLETION_REPORT.md)。本地模拟器为 API 35；不能据此认定 API 37 的局域网权限流程已通过。

## 前一轮第2～6步结果

17 项 JVM 测试、8 项 Python 自测已通过。实时 TCP/UDP 播放、MediaStore 回放及 View 重绑有实际像素检查；默认五分钟双路分段已实际运行约十分钟；AAC/音频丢弃、断线重连、进程退出恢复、pending 删除保护均有显式本地证据。ENOSPC 和真实设备场景没有通过验收。详情见 [最新验证记录](STEPS_2_6_VALIDATION.md)。

## 第一阶段历史已执行

- core JVM：3 PASS（文件名、凭据对象脱敏、非法协议拒绝）。
- tools Python：3 PASS（HEVC 检查路径、非关键帧首包拒绝、DTS 非递增拒绝）。仅自测工具逻辑。
- Android 15 AVD：data 2 PASS（Keystore 加密/篡改；Room/MediaStore pending 对账）；media 1 PASS（JNI 加载/错误返回/重复停止安全）。
- Debug APK 安装启动 PASS；不代表播放或前台服务通过。

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew :data:connectedDebugAndroidTest :media:connectedDebugAndroidTest
python3 tools/test_check_media.py
# 需要另行安装 ffprobe，路径指向已导出的本地录像
python3 tools/check-media.py recording.mp4
# 进一步完整解码检查，必须使用有对应解码器的 FFmpeg
ffmpeg -v error -i recording.mp4 -f null -
```

## 第二阶段历史本地合成流

H.264/H.265 TCP 分段及解码、双路独立停止、MediaStore 有效文件发布删除、模拟器短时后台息屏已经实测通过。该轮 UDP 和 LibVLC H.264 路线有失败，五分钟及真实设备验收未完成。后续已修正模拟器 UDP 回程并完成默认五分钟长测；LibVLC H.264 同会话路线未选为正式录像引擎，真实设备验收仍未完成。详情和原始结果见 [第二阶段报告](PHASE2_VALIDATION.md)。新增依赖测试通过 runner 参数显式启用；未传参数时跳过，不能把跳过当通过。

## 待执行完整验收矩阵（真实设备结果仍待补充）

| 维度 | 场景 | 通过标准 | 当前状态 |
|---|---|---|---|
| 播放 | H.264/H.265 × TCP/UDP | 有连续画面、信息正确；硬件后端有设备证据 | NOT_RUN |
| 认证 | 正确/错误凭据、特殊字符 | 正确可连接；错误停止无意义重试；日志无凭据 | 本地 Basic 合成源 PASS；真实设备 NOT_RUN |
| sout | duplicate/display/std MP4；原会话 record API | 画面连续、MP4 合法、停止收尾，服务端证据确认单会话 | NOT_RUN |
| 动态开始停止 | 预览中录像，重复 20 次 | 不泄露连接、句柄；原会话路线不得重连冒充动态控制 | NOT_RUN |
| 音频 | AAC、无音频、G.711/G.726 等 | AAC 复制；不兼容提示且视频不转码 | NOT_RUN |
| 分段 | 至少连续三段、长 GOP、手动早停 | 达 300 秒后的关键帧切段；每段独立解码，无意外缺口 | NOT_RUN |
| 无重编码 | 已知源与录像视频 packet 对照 | 编码、参数及规范化后 payload 一致 | NOT_RUN |
| 异常 | 断网、拔摄像头、参数变化 | INTERRUPTED；重连新段；旧段不续写 | NOT_RUN |
| 停止 | 页面、通知、录制中删设备 | 所需 trailer 完成；句柄、锁、通知释放 | NOT_RUN |
| 存储 | MediaStore、相册、查询/删除 | 发布前隐藏，发布后可见可回放，活跃段不可删除 | NOT_RUN |
| 空间 | 起始不足、录制途中 ENOSPC、复制不足 | 用户可见失败；无虚假成功索引；已完成视频保留 | NOT_RUN |
| 恢复 | kill 进程、部分 fragment、发布中崩溃 | 识别并恢复可用片段；坏文件不发布、不误删历史视频 | NOT_RUN |
| 后台 | API 29、31、33、34、35、36及更新稳定版 | 合法从前台启动；权限拒绝无崩溃；后台可录像 | NOT_RUN |
| 锁屏 | 真机切后台/锁屏至少30分钟 | 文件内容覆盖期间，无无故停录，通知可停止 | NOT_RUN |
| 长时 | 连续至少8小时 | 不误用受六小时限制的服务类型；统计热量/耗电/丢包 | NOT_RUN |
| 并发 | 每台摄像头逐步增加连接 | 明确拒绝阈值；分别测仅播放、仅录像、播放+录像 | NOT_RUN |
| 多路 | 多设备逐步增加配额 | 记录 CPU/内存/温度/带宽/丢包，不仅看连接成功 | NOT_RUN |

## 实测步骤

1. 在局域网准备官方 MediaMTX 或真实摄像头。测试源使用已知合法 H.264/H.265 文件，通过 FFmpeg `-re -stream_loop -1 -i <fixture> -c copy -f rtsp <local-server-url>` 发布；不在命令中写摄像头密码。源服务器必须可被 Android 设备访问。模拟器访问主机通常使用 10.0.2.2。
2. 单独验证预览，再录像；记录摄像头 RTSP 会话数。两路方案必须测到摄像头拒绝连接，报告阈值和故障表现。
3. 录制三段，停止。导出 MP4，执行媒体检查与完整解码，在 App 中回放首/中/尾与分段接缝。
4. sout 独立实验在断开后提取私有文件。检查 API 调用返回值、完成事件、文件轨道、会话数。桌面 VLC 结果不能替代 Android 结果。
5. 在真实手机执行后台、锁屏、通知停止、Wi-Fi 关闭/恢复、进程退出与空间不足测试，记录视频时间范围而非只检查文件存在。
6. 递增并发配额，至少测10分钟稳定期与温升。得出具体机型建议上限，不能外推到所有手机。

## Android 17 局域网拒绝验收

状态：NOT_RUN。本次没有 API 37 运行环境。此用例验证系统拒绝权限时，会话管理器阻止预览/sout，录像进入 FAILED、不启动原生录像及不重试；不能替代真实 TCP/UDP 授权连通性或系统弹窗人工测试。

先在专用 API 37+ 设备安装 app 与 app AndroidTest APK，确认 `adb shell getprop ro.build.version.sdk` 至少为 37，再执行：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm revoke com.rtspmonitor.app android.permission.ACCESS_LOCAL_NETWORK
adb shell am instrument -w -e class com.rtspmonitor.app.LocalNetworkPermissionTest -e localNetworkDenied true com.rtspmonitor.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell pm grant com.rtspmonitor.app android.permission.ACCESS_LOCAL_NETWORK
```

还须人工检查：点击连接/录像触发权限请求；拒绝后提示且不录制；允许后 H.264/H.265 × TCP/UDP 可连接；停止及本地回放在权限拒绝时仍可用；录制期间从设置撤销权限后没有无限重试。撤销权限可能由系统终止应用进程，应另行检查恢复流程。

API 35 上的权限拒绝通知验证可复现：`./tools/run-local-spike.sh notifications-denied`。该脚本先拒绝 POST_NOTIFICATIONS 并设置拒绝标志，测试后台息屏录制与页面停止，结束后恢复该测试安装的通知权限；它不是 Android 17 局域网权限测试。
