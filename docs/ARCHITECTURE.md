# 架构

## 模块与所有权

```text
app (Compose + ViewModel + ForegroundService)
 ├── core (接口、状态、连接配置)
 ├── data → core (Room、Keystore、MediaStore)
 └── media → core (LibVLC、libavformat JNI)
```

`MonitorApp` 通过构造器装配依赖。`CameraSessionManager` 按设备 UUID 管理 `Session`，每个 Session 保存播放器、录像引擎、任务与可观察状态；开始、停止和删除通过每设备命令锁串行处理。连接状态描述预览，录像状态独立描述录像链路，因此关闭预览时仍可录像。状态包含 reconnectCount、lastError；媒体信息来自播放器独立 StateFlow。

界面只发送设备操作和服务命令。LibVLC 类型不进入 UI；Android View 由 media 工厂创建并绑定。ViewModel 观察设备和录像索引；Activity 销毁不停止服务或录像。横屏时解绑、重绑视频输出，录像会话保持在应用会话管理器中。

设备保存数量没有固定上限。当前安全默认为一路解码、一路录像，可在验证页面增加配额。配额增加不代表硬件可承受，且不能绕过摄像头连接上限。未引入多画面布局、设备发现、云端或自动启动。

## 播放与录像

LibVLC 3.7.7 负责 RTSP 与本地视频播放，请求硬件解码并允许回退。API 无法直接证明实际硬解后端，界面明确提示待确认。`probeSout` 尝试 duplicate/display/std MP4，`probeRecord` 封装原会话 record API，保留为诊断入口；当前录制主路径使用独立引擎。

当前可用录像代码为独立 libavformat 会话：同设备预览加录像通常需要两个 RTSP 会话。禁止把它记录成单连接方案。每个录像任务在 IO 线程运行 native read loop，停止通过原子标志和 AVIOInterruptCB 取消；连接和读取使用 15 秒截止时间。

仅接受 H.264/HEVC 视频，AAC 音频复制，其他音频提示后忽略。没有启用视频编码器。H.264/AAC 的流信息探测会有限解码以判断解码延迟、音频帧长和时间戳；写流循环仍不解码重编码。复用输入时间基，重置各段时间原点，保留 PTS/DTS 差值，拒绝非递增 DTS。发现新 extradata 时中断并重连。每段达到 300 秒后在下一视频关键帧切换，不保证精确五分钟，也不制造关键帧。

native 先写私有 `.part` fragmented MP4，正常完成 trailer 后改名 `.mp4` 并发出 segment 事件。已封装片段进入 SegmentPublisher 独立发布队列，最多排队两段，另外一段可以正在发布；队列满或发布失败会停止该路录像并保留临时文件，停止时等待发布收尾。已完成分片通过 AVIO flush 及时离开用户态缓冲。异常退出的 `.part` 先检查顶层 MP4 box 边界，裁去未完成尾片，再用 Android MediaExtractor 读取完整视频样本；失败改为 `.damaged`，不自动删除已有录像。

## 服务与状态

服务从前台用户操作启动，使用 `connectedDevice` 类型，通知提供停止全部。`CHANGE_NETWORK_STATE` 用于外部网络设备连接场景的服务运行前提；未声明本机 CAMERA、MICROPHONE、媒体投屏或媒体处理服务类型。后台启动被系统拒绝时显示错误。Android 13 通知拒绝不被误认为获得后台通知显示权限；拒绝后提示用户从页面停止录像。compile/target 为 37，Android 17 的 ACCESS_LOCAL_NETWORK 在用户连接或开始录像时请求；未授权阻止预览与 sout，录像进入 FAILED，重连前再次检查。停止与本地回放不需要此权限。

活跃录像/准备/收尾期间使用有超时、定期续期的 PARTIAL_WAKE_LOCK；退避重连时释放。重连间隔为 1、2、4、8、16、30 秒上限；网络恢复后另建新目录/新段。认证失败和不支持的视频编码进入 FAILED，不无限重试。服务 START_NOT_STICKY，不实现开机录像或进程被杀后的静默自动恢复。

正常停止先通知原生循环退出，再等待收尾与发布，最后销毁句柄。通知、唤醒锁和服务在全部录像停止后清理。系统强制停止、厂商省电策略与用户停止应用仍可能中断录像。

## 数据与安全

Room 保存 Camera、Recording；录像索引只在 MediaStore 发布成功后写入。设备的整个连接对象（包括 URL）用 Android Keystore AES-GCM 加密；数据库不保存明文凭据。禁用云备份与设备迁移，避免恢复无法解密的数据。密钥失效必须重新输入设备信息。

native FFmpeg 日志关闭，LibVLC quiet；上层使用固定错误文本，不输出原始异常或 URI。凭据只在连接期间进入内存/原生库，不能承诺擦除所有托管字符串。输入仅接受有效 rtsp:// URL；本阶段未启用 RTSPS/TLS。

MediaStore 发布路径为 Movies/RTSPMonitor；文件名为清理后的设备名、日期时间和持久化递增序号。临时文件和 URI journal 保存在 noBackupFilesDir；发布时 IS_PENDING=1，复制、关闭并检查成功后改为 0。删除操作带 IS_PENDING=0 条件，不删除正在发布的行。空间预检保留 128 MiB，并为发布复制预留额外文件大小；实际 ENOSPC 仍按失败处理。

发布前读取视频轨道全部样本，单样本上限 32 MiB；这是结构检查，不代替完整解码。

恢复重用 journal 中的 URI；启动时对账本应用 Movies/RTSPMonitor 中无 journal 引用的 pending 项并清理，删除相册外部删除后遗留的 Room 索引。对账失败时提示，不把权限或 provider 错误当作录像已删除。恢复与新录像通过页面 ready 状态隔离；跨版本 provider 行为仍需实测。

视频显示由 SurfaceView 和透明辅助 Surface 构成，尺寸读取视频布局回调。Surface 就绪和轨道重选配合处理，以支持解绑/重绑；本地回放持有 MediaStore 文件描述符，在停止或切换输入时关闭。本地回放的播放器与 URI 归 ViewModel，Activity 重建保留；RTSP 会话仍由应用会话管理器持有。

最新交付与验收边界见[完成报告](COMPLETION_REPORT.md)，前一轮媒体证据见[第2～6步验证记录](STEPS_2_6_VALIDATION.md)。
