# 安卓维护与兼容性说明

项目采用 Compose、View 和 Activity 共存的方式维护，保留成熟阅读器与音视频组件。后续升级以修复实际兼容性、可靠性和安全问题为目标，不以统一技术栈为条件。

界面按可用窗口适配手机、横屏、平板、分屏和自由窗口，并考虑大字号、输入法、系统导航和无障碍操作。阅读位置、草稿与任务应在窗口变化和系统重建后保持。

备份恢复、下载和导出包含中断恢复及错误提示；涉及用户数据的修改应验证旧版本升级、失败回滚及进程终止后的恢复。更改阅读或媒体入口时，需检查返回路径和播放位置。

本轮 35 项计划已按模拟器范围完成：API 33 和 35 的正式包核心流程通过，API 26、34、36 和 37 有对应专项证据；arm64 在 16 KB 页面环境运行通过，armeabi-v7a 和 x86_64 完成构建与静态检查。折叠屏和真机未纳入本轮验收。历史原始报告保存在本地 `app/build/reports/`，该目录不随 Git 提交。

常规检查：

```bash
./gradlew testAppDebugUnitTest lintAppDebug assembleAppRelease
python3 -B -m unittest discover -s tools -p 'test_*.py'
```

设备回归入口和原生库、Proto 检查位于 `tools/`，CI 配置位于 `.github/workflows/android-quality.yml`。设备测试应使用专用模拟器，并核实 APK 与测试范围；远端 CI 结果以实际运行记录为准。

安装包版本号由 `gradle.properties` 中的 `appVersionCode` 管理，不依赖 Git 提交数量。每次发布新版本前，须将其提高到大于所有已发布或供覆盖安装的版本；本轮从 `38707` 开始。
