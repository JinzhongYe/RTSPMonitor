# RTSP Monitor 技术验证工程

Android 原生 Kotlin / Compose 工程，最低 Android 10，当前 compile/target 37。此版本用于技术验证，**真实摄像头与锁屏验收尚未完成**。


[下载 0.3-spike 测试安装包](https://github.com/JinzhongYe/RTSPMonitor/releases/tag/v0.3-spike)（私有仓库需登录 GitHub；Android Debug 签名）。

```bash
# 需要 JDK 21、Android SDK 37.0、NDK 27.0.12077973、make、curl、tar
# local.properties 中配置 sdk.dir；原生脚本通过 ANDROID_SDK_ROOT 定位 SDK
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew :data:connectedDebugAndroidTest :media:connectedDebugAndroidTest
python3 tools/test_check_media.py
```

首次构建自动下载并校验 FFmpeg 源码，编译 arm64-v8a / x86_64 原生库。使用 Linux 时显式设置 ANDROID_SDK_ROOT。下载与原生编译需要网络和数分钟，后续由 Gradle 增量检查。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。没有打包 32 位 ABI。

页面中输入设备名称、RTSP URL 和独立凭据，选择 TCP/UDP 后保存。连接用于预览；录像按钮启动独立连接的前台服务；停止按钮完成收尾。通知可停止全部录像。发布成功的录像出现在页面下方，可回放和删除。首次启动会尝试恢复遗留录像；失败片段保留，可手动清理。默认同时允许一路预览、一路录像，测试配额可逐次增加。

sout 按钮是显式实验：重建播放会话，同时尝试显示与写 MP4；断开预览后收尾。它不等于已实现“保持原会话动态开始/停止录像”。实验文件保存在应用私有 `no_backup/sout-probe/`，不会自动发布。

- [完成报告与验收边界](docs/COMPLETION_REPORT.md)
- [架构](docs/ARCHITECTURE.md)
- [技术验证结果与问题](docs/TECHNICAL_SPIKE.md)
- [测试矩阵与复现](docs/TEST_MATRIX.md)
- [依赖、许可证与分发前检查](docs/DEPENDENCIES.md)

当前 0.3-spike 已完成本地 TCP/UDP 出帧、AAC、默认五分钟双路录像、MediaStore 回放、模拟器后台与进程恢复验证。最新结果见[完成报告](docs/COMPLETION_REPORT.md)，前一轮证据与复现脚本见 [第2～6步记录](docs/STEPS_2_6_VALIDATION.md)；旧阶段失败与取舍保留在历史报告。
