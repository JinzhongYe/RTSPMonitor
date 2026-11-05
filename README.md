# RTSP Monitor

Android 单路摄像头监控与后台录像工具，最低 Android 10。

[下载 v0.4.0 测试版 APK](https://github.com/JinzhongYe/RTSPMonitor/releases/tag/v0.4.0)

主页面提供实时预览、连接状态、录像控制、横屏全屏及录像回放。地址与凭据在设置中修改；默认地址为 `rtsp://192.168.0.210:554/stream1`，用户名 `admin`，默认 TCP。首次使用需自行输入密码，凭据使用 Android Keystore 加密保存在本机。

只允许同一摄像头的一路预览和一路录像。录像使用独立连接，后台前台服务运行；默认每五分钟分段保存。更新配置时先停止录像并完成收尾。旧版存在多个设备时需选择使用的一路；其他设备记录和历史录像保留。

## 安装与升级

v0.4.0 使用新的固定 Debug 测试签名，与 v0.3-spike 签名不同，不能直接覆盖安装。卸载旧版前先备份所需录像；卸载会清除设备配置、凭据和应用私有临时录像。新签名密钥保存在本机构建账户及 GitHub Actions Secret，后续测试版沿用。

## 构建与验证

Linux/macOS 下需要 JDK 21、Android SDK 37、NDK 27.0.12077973、bash、make、curl、tar。设置 `ANDROID_SDK_ROOT` 后运行：

```sh
bash gradlew assembleDebug testDebugUnitTest lintDebug :app:assembleDebugAndroidTest
python3 -m unittest discover -s tools -p 'test_*.py'
```

首次构建会下载并校验 FFmpeg，编译 arm64-v8a / x86_64。GitHub Actions 自动执行构建、检查、配置回归测试和启动截图，使用 Secret `DEBUG_KEYSTORE` 中的 Base64 编码固定测试密钥。

真实摄像头、锁屏长时录像及 API 37 实机权限验收需要对应设备与局域网，不能由构建通过代替。既有验证记录针对 v0.3-spike，不代表 v0.4.0 已实测。

- [架构](docs/ARCHITECTURE.md)
- [既有技术验证报告](docs/COMPLETION_REPORT.md)
- [依赖与分发材料](docs/DEPENDENCIES.md)
