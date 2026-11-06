# RTSP Monitor

Android 单路摄像头监控、活动检测与后台录像，最低 Android 10。

[下载 v0.8.0 测试版](https://github.com/JinzhongYe/RTSPMonitor/releases/tag/v0.8.0)

## 使用

在设置中填写摄像头密码并保存。默认地址为 `rtsp://192.168.0.210:554/stream1`，用户名 `admin`，默认 TCP；密码不随安装包提供，使用 Android Keystore 在本机加密保存。

- **连接**：查看实时画面。按 Home、返回桌面或灭屏后，运行任务由前台服务继续管理；重新打开应用恢复实时预览。
- **自动监控**：首次手动开启，之后记住选择。手机本地识别人形或明显画面变化，不上传图像。连续两帧满足条件后触发自动录像；连续无活动 30 秒后停止。
- **手动录像**：一直录到点击“停止手动录像”。自动监控和手动录像共用一个录像器；自动事件结束不停止手动录像，停止手动录像也不关闭自动监控。
- **保存到相册**：新录像先保存在应用内部，完成后可导出到 `DCIM/RTSPMonitor/`。重复导出不会产生重复副本。删除应用内录像不会删除已导出的副本。

默认约五分钟一段 MP4，按关键帧切分，最后不足五分钟的片段也保存。自动录像从触发后的首个关键帧开始，不包含触发前的视频。当前摄像头的 G.711 音频不写入 MP4，录像仅保存视频。

自动活动通知每个事件提醒一次，状态通知常驻并提供“停止所有任务”。通知未授权时检测和录像仍可运行，但无法弹出活动提醒。

## 升级和数据

v0.5.0 沿用 v0.4.0 的固定测试签名，支持覆盖升级。旧版已经保存在系统媒体库的录像保留原位置，数据库自动迁移。

**卸载会清除应用内部尚未导出的录像、设备配置及凭据，请先导出需要保留的视频。** 已导出的相册副本独立保留。旧版相册录像的删除按钮会删除其原文件，界面有确认提示。

强制停止、进程被系统结束或手机重启期间，录像可能中断；再次打开应用会恢复上次运行意图并处理遗留片段。不提供开机自启动。人形识别可能误报、漏报；持续解码和检测会增加耗电，厂商省电策略仍可能影响后台运行。

## 构建

Linux/macOS 使用 JDK 21、SDK Platform 37.0、Build Tools 37.0.0、NDK 27.0.12077973、bash、make、curl 和 tar。设置 `ANDROID_SDK_ROOT` 后运行：

```sh
bash gradlew assembleDebug testDebugUnitTest lintDebug :app:assembleDebugAndroidTest
python3 -m unittest discover -s tools -p 'test_*.py'
```

原生构建下载并校验 FFmpeg，生成 arm64-v8a / x86_64。检测模型随 APK 打包，版本、来源与许可证见 [模型说明](docs/EFFICIENTDET_MODEL.md)。发布步骤显式使用 GitHub Secret `DEBUG_KEYSTORE` 签名并验证证书。

- [架构及早期验证资料](docs/ARCHITECTURE.md)
- [v0.3 技术验证报告](docs/COMPLETION_REPORT.md)
- [依赖和分发材料](docs/DEPENDENCIES.md)

最新本地开发版本 0.6.0：独立回放、来源分类、时间范围文件名及活动通知隔离，详见[本轮实现与测试记录](docs/PLAYBACK_RECORDING_UPDATE.md)。[下载 0.6.0 预发布安装包](https://github.com/JinzhongYe/RTSPMonitor/releases/tag/v0.6.0)（调试签名；活动通知真机验收仍待完成）。

本地开发版本 0.7.0 按参考照片优化了回放和录像浏览布局，见[布局与截图记录](docs/UI_0.7.0.md)。[下载 0.7.0 预发布安装包](https://github.com/JinzhongYe/RTSPMonitor/releases/tag/v0.7.0)。

0.8.0 新增独立的“检测与录像设置”：可调整人形置信分、变化面积、亮度差和手动分段时长；设置持久化且运行中生效，活动录像保持5分钟，重叠时手动优先。详见[设置验证报告](docs/SETTINGS_0.8.0.md)。
