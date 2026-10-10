# 阅读 (Legado) 精简版

基于开源小说阅读器 **Legado（阅读 3.0）** 深度裁剪定制的纯净版本，专注于本地与在线小说阅读核心体验。

## 精简与优化特性

- **纯粹阅读体验**：保留完整的自定义书源、发现、全文搜索、替换净化、TXT 目录规则、字典规则、朗读（TTS）及阅读界面高度自定义能力。
- **彻底移除冗余模块**：
  - 移除 **RSS 订阅**模块
  - 移除 **Web 局域网服务 / MCP 服务 / 直链上传**及相关前端静态资源、Ktor/Firebase 等依赖
  - 移除 **后台定时任务（AutoTask）**，告别后台常驻唤醒，按需手动刷新即可
- **100% 备份兼容**：完全兼容原版阅读的本地备份与 WebDAV 备份恢复（自动忽略已移除模块的历史配置数据）。
- **独立架构分包（体积减半）**：构建时按 `arm64-v8a`、`armeabi-v7a`、`x86_64` 独立分包，`arm64-v8a` 正式版体积仅约 **18 MB**。

## 本地构建与测试

环境要求：**JDK 21**、**Android SDK (API 36)**。

```bash
# 构建 Debug 分包 APK
./gradlew :app:assembleAppDebug

# 构建 Release 正式版分包 APK（开启 R8 混淆与资源压缩）
./gradlew :app:assembleAppRelease

# 运行单元测试
./gradlew testAppDebugUnitTest
```

构建产物位于：
- Debug：`app/build/outputs/apk/app/debug/`
- Release：`app/build/outputs/apk/app/release/`

## 开源协议

本项目基于 [GPL-3.0 License](LICENSE) 开源。
