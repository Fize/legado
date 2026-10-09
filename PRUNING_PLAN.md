# Legado 二次开发：重命名与轻量化裁剪计划

## 核心目标
打造一款专注、纯粹、轻量的高颜值看书/听书 App。剥离一切与阅读无关的边缘功能，降低系统开销，减小安装包体积，为后续的 UI 现代化改造打好基础。

---

## 第〇阶段：本地构建环境安装（前置，必须先完成）
**目标**：在本机具备编译能力，使后续每一步裁剪都能立即验证，而不是盲改。

**项目要求（已从仓库确认）**：JDK 21（`sourceCompatibility = 21`）、`compileSdk 36`、`targetSdk 36`、`minSdk 23`，Gradle 使用仓库自带的 `gradlew`（无需另装）。

*   **任务 1：安装 JDK 21**
    *   `brew install --cask temurin@21`
    *   在 `~/.zshrc` 添加：`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
    *   验证：`java -version` 输出 21.x。
*   **任务 2：安装 Android SDK**（二选一）
    *   方案 A（推荐，带 IDE）：安装 Android Studio（`brew install --cask android-studio`），首次启动按向导安装 SDK，再在 SDK Manager 勾选 **Android SDK Platform 36** 与 **Build-Tools**；如构建报缺失 NDK/CMake，再按提示补装。
    *   方案 B（仅命令行）：`brew install --cask android-commandlinetools`，然后
        `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`，并接受协议：`sdkmanager --licenses`。
    *   设置：`export ANDROID_HOME=$HOME/Library/Android/sdk`（方案 B 按实际路径），并在项目根目录创建 `local.properties`：`sdk.dir=<ANDROID_HOME 的路径>`（该文件不应提交到 Git）。
*   **任务 3：基线编译（裁剪前的对照）**
    *   在干净代码上执行：`./gradlew assembleDebug`
    *   首次会下载 Gradle 与依赖，耗时较长；若网络受限需配置代理或镜像。
    *   **验收标准**：命令成功并产出 `app/build/outputs/apk/**/*.apk`。基线不通过，不进入后续阶段。
*   **任务 4：确认签名/Firebase 配置不阻塞 Debug 构建**
    *   如果基线因 `google-services.json` 或签名文件缺失而失败，记录原因，留待第二阶段“移除 Firebase”一并处理。
*   **任务 5（可选）**：连接真机或创建模拟器（仓库已有 `avd.sh`），用于第四阶段的功能测试。

> 备选：若本机确实无法安装，可改用 GitHub Actions 云端编译（仓库已有 `.github/workflows`），但每次验证需推送并等待，效率较低。

---

## 第一阶段：专属身份剥离（修改包名）
**目标**：确立新项目的唯一标识，彻底与官方版和其他 Fork 版本解绑，避免覆盖安装冲突。
*   **任务 1**：修改 `app/build.gradle` 中的 `applicationId`（从 `com.legado.app` 改为你指定的专属包名）。
*   **任务 2**：修改 `google-services.json`（如果需要保留统计）或直接移除。
*   **任务 3**：清理代码中可能硬编码残留的包名（如 `FileProvider` 权限的 Authority 检查）。

---

## 第二阶段：核心冗余模块裁剪（大刀阔斧做减法）

基于“专注看书听书”的理念，以下功能被列为**高优裁剪对象**：

### 1. 彻底移除“RSS 订阅”模块
*   **理由**：RSS 订阅（主要用于看博客、新闻聚合）与“网文/本地小说阅读”的体验割裂，使用率低，但占用了主界面底部的一个主 Tab，并且在数据库里有庞大的独立表结构。
*   **操作**：
    *   删除 `ui/rss` 整个包及其所有的 Activity/Fragment。
    *   从 Room 数据库表结构中删除 `RssSource`、`RssStar` 等相关实体表。
    *   移除底栏导航 `main_bnv.xml` 中的“订阅”Tab，使底栏精简为**“书架、发现、我的”**三栏。

### 2. 彻底移除“内置 Web 服务器”与前端页面
*   **理由**：Legado 内置了一个微型服务器（NanoHTTPD）以及一整套 Vue 3 网页应用（`modules/web`），用于在电脑浏览器上看书和配置书源。虽然极客，但这使得 App 非常沉重。
*   **操作**：
    *   删除整个 `modules/web` 前端目录。
    *   从 `build.gradle` 移除 `NanoHTTPD` 相关依赖。
    *   删除 Java 代码中的 `io.legado.app.web` 整个包（所有前后端交互接口）。
    *   从设置界面中移除“Web 服务”开关与入口。

### 3. 移除实验性 AI 服务端 (MCP Server)
*   **理由**：上游新引入的 Model Context Protocol 协议支持，主要用于大模型 Agent 交互，对普通阅读用户毫无意义且引入了 Ktor 框架依赖，增加包体。
*   **操作**：
    *   从依赖中移除 `mcp.sdk.server` 和 `ktor.server.cio`。
    *   删除相关的初始化和服务代码。

### 4. 移除数据收集与无用云服务配置
*   **理由**：保持开源阅读的绝对纯净。
*   **操作**：
    *   移除 `Firebase Analytics`（埋点）和 `Firebase Perf`（性能追踪）。
    *   移除极其冷门的 `DirectLinkUpload`（直链网盘上传配置）机制。

---

## 第三阶段：界面与设置清理（用户感知层）
**目标**：让精简后的 App 在界面上逻辑自洽。
*   **任务 1**：精简“设置”面板，移除上述已废弃功能的相关设置项。
*   **任务 2**：精简“备份与恢复”功能（只备份书源、书架、书签、设置，不再打包订阅源等）。
*   **任务 3**：梳理“我的”界面菜单，去掉冗余的占位入口。

---

## 第四阶段：全量编译验证与回滚预案
*   **验证方法**：清理并执行 `Rebuild Project`，确保没有任何断链的 API 引用。
*   **测试流程**：
    1. 导入 TXT/EPUB 验证本地阅读。
    2. 导入书源验证网络搜索、看书、更换目录。
    3. 启动 TTS 听书引擎验证语音播放。
    4. 测试本地备份与恢复。
*   **回滚预案**：所有的裁剪都在独立的 Git Commit 中进行。如遇到影响核心看书引擎的深层依赖，随时可通过 `git revert` 撤销单个裁剪动作。
