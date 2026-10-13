# 阅读 (Legado) 全量 Jetpack Compose 迁移计划（Zero UI XML）

## 1. 现状盘点与总体目标

经过孤儿文件清理后，当前项目中仍在使用、待迁移为 Jetpack Compose 的传统 Android UI XML 共计 **301 个文件**：

| 资源目录 | 剩余文件数 | 当前用途 | 迁移后目标状态 |
| :--- | :---: | :--- | :--- |
| `app/src/main/res/layout` | 216 | 34 个 `activity_*.xml`、10 个 `fragment_*.xml`、68 个 `dialog_*.xml`、67 个 `item_*.xml`、37 个 `view_*/popup_*/video_*` | **0 个文件（全部删除）** |
| `app/src/main/res/layout-land` | 3 | `activity_audio_play.xml`、`activity_book_info.xml`、`view_book_intro.xml` | **0 个文件（全部删除）** |
| `app/src/main/res/menu` | 71 | 顶栏 ActionBar 菜单、列表多选弹出菜单、上下文菜单 | **0 个文件（全部删除）** |
| `app/src/main/res/xml` (UI 设置) | 11 | `pref_main.xml`、`pref_config_*.xml` (9 个)、`about.xml` | **0 个文件（全部删除）** |
| `app/src/main/res/xml` (系统元数据) | 5 | `file_paths.xml`、`network_security_config.xml`、`backup_rules.xml`、`data_extraction_rules.xml`、`spen_remote_actions.xml` | **保留（Android OS 系统规范要求，非 UI 代码）** |

> [!IMPORTANT]
> **最终架构目标**：
> 1. **100% 纯 Jetpack Compose UI**：彻底移除 `res/layout*`、`res/menu` 与 `res/xml/pref_*.xml`，在 [`app/build.gradle.kts`](file:///Users/xiaobaitu/github.com/legado/app/build.gradle.kts) 中关闭 `viewBinding = true`。
> 2. **升级至最新版 Jetpack Compose 生态**：从当前 `compose-bom:2025.02.00` 升级至 Google Maven 最新稳定版 **`compose-bom:2026.09.00`**（Compose UI/Foundation `1.12.1`、Material 3 `1.4.0`、Material 3 Adaptive `1.3.0`）。
> 3. **原生响应式大屏架构**：使用官方 `androidx.compose.material3.adaptive` 的 `ListDetailPaneScaffold` 与 `WindowSizeClass`，从根源上杜绝传统 View + `FragmentTransaction` + `RecyclerView` 异步测量造成的平板横竖屏挤压、留白错位与控件裁切问题。

---

## 2. 目标技术栈与架构设计

```mermaid
flowchart TD
    subgraph TechStack["Jetpack Compose 最新技术栈 (BOM 2026.09.00)"]
        UI["Compose UI / Foundation 1.12.1"]
        M3["Material 3 (1.4.0) + Dynamic Color"]
        Adaptive["Material 3 Adaptive 1.3.0 (ListDetailPaneScaffold / NavigationSuite)"]
        Nav["Navigation Compose + Lifecycle Runtime Compose 2.10.0"]
        Image["Glide Compose / Custom Canvas Renderers"]
    end

    subgraph CoreModules["纯 Compose 业务模块层"]
        Shell["主界面壳层 (MainScreen: 书架 / 发现 / 我的)"]
        Pref["声明式设置引擎 (Compose Preference DSL, 替代 PreferenceFragmentCompat)"]
        Source["书源与规则中心 (管理 / 编辑 / 调试 / 订阅)"]
        Dialogs["统一自适应弹窗体系 (ModalBottomSheet / AlertDialog)"]
        Manga["漫画阅读器 (Compose LazyColumn 条漫 + Canvas 仿真翻页)"]
        EBook["电子书阅读器 (ChapterProvider 排版模型 + Compose Canvas 页面渲染)"]
    end

    TechStack --> CoreModules
```

### 2.1 依赖升级与裁剪清单（[`gradle/libs.versions.toml`](file:///Users/xiaobaitu/github.com/legado/gradle/libs.versions.toml)）
- **新增 / 升级依赖**：
  - `composeBom = "2026.09.00"`
  - `androidx.compose.material3.adaptive:adaptive`、`adaptive-layout`、`adaptive-navigation` (`1.3.0`)
  - `androidx.compose.material3:material3-adaptive-navigation-suite` (`1.4.0`)
  - `androidx.lifecycle:lifecycle-runtime-compose`、`androidx.lifecycle:lifecycle-viewmodel-compose` (`2.10.0`)
  - `androidx.navigation:navigation-compose`
- **迁移完成后移除的历史遗留 UI 依赖**：
  - `androidx.preference:preference` / `preference-ktx` 及项目中整个 `io.legado.app.lib.prefs.*` 包
  - `androidx.recyclerview:recyclerview`、`androidx.viewpager2:viewpager2`、`androidx.constraintlayout:constraintlayout`、`androidx.swiperefreshlayout:swiperefreshlayout`、`com.google.android.flexbox:flexbox`、`com.jaredrummler:colorpicker`
  - 项目中 `io.legado.app.ui.widget.recycler.*` 与 `io.legado.app.base.adapter.*` 全部旧版 Adapter 基类

### 2.2 第三方原生 SDK 视图的 Compose 桥接策略
对于极少数底层基于 Surface / OpenGL / 自绘编辑器的第三方组件，采用 Compose 官方 `AndroidView` 组件内联封装（不再需要任何 `.xml` 布局文件）：
1. **代码编辑器（`SoraEditor`）**：通过 `AndroidView(factory = { CodeEditor(it) })` 嵌入，外围工具栏、快捷符号栏、搜索框全部使用 Compose。
2. **网页容器（`WebView`）**：通过 `AndroidView(factory = { WebView(it) })` 嵌入，顶部进度条、地址栏与菜单全部使用 Compose。
3. **视频解码画面（`ExoPlayer` / `GSYVideoPlayer`）**：通过 `AndroidView` 承载视频纹理层，播放控制面板、选集抽屉、倍速选择器、悬浮窗控件全部改为 Compose 覆盖层。
4. **相机扫码（`ZXing` PreviewView）**：通过 `AndroidView` 承载相机预览流，取景框动画与底部操作栏改为 Compose。

---

## 3. 分阶段实施路线图（共 6 个阶段）

为保证每一步都可编译、可测试、可独立验收，按模块耦合度由低到高分为 **Phase 0 ~ Phase 6** 推进，**每完成一个模块立即物理删除对应的 `.xml` 文件**。

### Phase 0：升级 Compose 2026.09 基座与通用 UI 组件库
- 升级 `composeBom = "2026.09.00"`，引入 Material 3 Adaptive (`1.3.0`) 与 Lifecycle Compose (`2.10.0`)。
- 构建 `io.legado.app.ui.compose.component` 通用组件库（`LegadoTopAppBar`、`LegadoSearchBar`、`LegadoOverflowMenu`、`LegadoSelectActionBar`、`LegadoBookCover`、`LegadoEmptyErrorView`、`LegadoAdaptiveDialog`），一次性替代 35+ 个通用 `view_*.xml` / `popup_*.xml` 与全部 71 个 `res/menu/*.xml` 菜单模式。

### Phase 1：设置中心与「我的」全模块 Compose 化（彻底废弃 `PreferenceFragmentCompat`）
- 构建纯 Compose 声明式设置组件库（`io.legado.app.ui.compose.prefs`），替代全部 11 个 `res/xml/pref_*.xml` + `about.xml`。
- 使用 `ListDetailPaneScaffold` 重写 `MyScreen` 与 10 个子配置页（备份、主题、其他、阅读、朗读、封面、欢迎页、关于），以及阅读记录、全部书签、替换净化、字典规则、TXT 目录规则、高亮规则、文件管理。

### Phase 2：主界面壳层、书架、发现、搜索与书籍详情 Compose 化
- `MainActivity` 转为纯 `setContent` 承载 `BookshelfScreen`、`ExploreScreen`、`MyScreen`。
- 重写书架（样式 1 / 样式 2、整理书架、本地导入、缓存导出）、发现页（`ListDetailPaneScaffold` 双栏分类 + 列表）、全局搜索、书籍详情（横竖屏自适应单/双栏）及目录页。

### Phase 3：书源生态、规则订阅、调试、登录与代码编辑器 Compose 化
- 重写书源管理（支持拖拽排序与批量操作）、书源编辑、JS 扩展编辑、书源调试、规则订阅、书源登录、WebView 浏览器、二维码扫描与代码编辑器外围界面。

### Phase 4：全量业务弹窗与通用对话框 Compose 化
- 将剩余全部 `dialog_*.xml`（换源、换封面、分组选择、词典、日志、WebDAV、文件选择器、更新弹窗等）重构为 Compose `ModalBottomSheet` / `AlertDialog`。

### Phase 5：音视频播放器、TTS 朗读与漫画阅读器 Compose 化
- 重写 `AudioPlayActivity`、`VideoPlayerActivity`（`AndroidView` 仅包裹底层解码视图，控制器全部 Compose 化）。
- 重写 `ReadMangaActivity`：菜单与设置弹窗全部 Compose 化；条漫使用 `LazyColumn` + 手势缩放，横屏双页/单页仿真翻页使用 Compose `Canvas` 复用 `MangaPageCurlRenderer` 与边缘取色算法。

### Phase 6：文字电子书阅读器 (`ReadBookActivity`) Compose 化与收官清理
- 迁移阅读器上下菜单、朗读悬浮条、长按选词气泡、正文搜索及全部阅读设置底部面板为 Compose。
- 保留 `ChapterProvider` / `TextChapterLayout` 核心排版算法（产出 `TextPage` 数据模型），使用 Compose `Canvas` 替代 `view_book_page.xml` 与 `activity_book_read.xml`。
- 删除 `res/layout*`、`res/menu`、`res/xml/pref_*.xml` 全部残留文件，在 `app/build.gradle.kts` 中移除 `viewBinding = true` 与旧版 View 依赖库。

---

## 4. 漫画阅读器迁移方案（独立后续阶段）

漫画阅读器的菜单、设置和外围容器可以继续使用 Compose；图片阅读区域单列为后续迁移阶段。在此阶段完成前，允许其保留原生 View 实现，但不得重新引入任何 `res/layout*` XML 文件。

### 4.1 目标架构

```mermaid
flowchart LR
    Data["ReadManga / ViewModel\n章节、页面、进度、加载"] --> State["MangaReaderUiState\n模式、方向、缩放、菜单"]
    State --> Webtoon["条漫\nLazyColumn"]
    State --> Single["横向单页\nHorizontalPager"]
    State --> Spread["横向双页\nHorizontalPager + MangaSpread"]
    Webtoon --> Page["MangaPage\n加载、重试、滤镜、长按保存"]
    Single --> Page
    Spread --> Page
```

### 4.2 实施顺序

1. 抽取 `MangaReaderUiState` 和事件接口，统一当前章节、页码、加载状态、阅读方向、缩放、预下载与菜单状态；`ReadManga` 继续负责内容加载、章节切换和进度持久化。
2. 使用 `LazyColumn` 迁移条漫模式。保留当前图片缓存、加载进度、失败重试、长按保存、颜色滤镜和接近尾部时的下一章预取行为。
3. 使用 `HorizontalPager` 迁移横向单页模式，以稳定页面 ID 恢复旋转和重建后的阅读位置。
4. 引入 `MangaSpread` 数据模型迁移横向双页与 RTL；单页、首尾页和跨页情况必须显式建模，不能再依赖 `RecyclerView` 的 adapter position 修正。
5. 实现单页 `ZoomState`：未缩放时由列表或 pager 滑动，放大后由图片优先处理平移；支持双击缩放、边界交接、长按保存与自动翻页暂停。
6. 最后将仿真卷页迁移为 Compose `Canvas`。在动画完成前，横向模式以 `HorizontalPager` 的普通翻页保证阅读可用。

### 4.3 验收条件

- 条漫、横向单页、横向双页和 RTL 均能正确恢复阅读进度。
- 图片加载、失败重试、预下载、灰阶、墨水屏、颜色滤镜、页脚、长按保存和自动翻页功能保持可用。
- 缩放状态下的拖动不误触翻页；未缩放时翻页和滚动响应正常。
- 删除 `MangaAdapter`、`MangaLayoutManager`、`WebtoonRecyclerView` 及只为漫画阅读保留的 RecyclerView 代码后，构建和关键阅读流程验证通过。

---

## 5. 非漫画动态 View 清理计划

`res/layout*`、`res/menu` 和偏好设置 XML 已删除，但最后一次 XML 清理把一部分界面改为 Kotlin 动态创建的 `ViewBinding`。本阶段的目标是将这些非漫画界面改为 Compose，而不是继续保留等价的动态 View；仅 `WebView`、视频解码画面、相机预览和代码编辑器等第三方原生控件可以通过 `AndroidView` 桥接。

### 5.1 范围与边界

| 范围 | 当前实现 | 目标实现 |
| --- | --- | --- |
| 通用编辑框 | `DialogEditTextBinding`、`DialogEditCodeBinding` | 统一 Compose 文本编辑弹窗；代码编辑器仅内部使用 `AndroidView(CodeView)` |
| Toast、批量操作条、详情滑条 | `ViewToastBinding`、`ViewSelectActionBarBinding`、`ViewDetailSeekBarBinding` | Compose `Snackbar`/自定义浮层、批量操作栏和滑条组件 |
| 文本阅读器菜单与页面容器 | `BaseReadBookActivity`、`TextActionMenu`、`PopupAction` | Compose 菜单、选择操作栏和阅读器容器；保留正文排版数据模型与 Canvas 渲染 |
| 设置与首页双栏 | `MyFragment`、`ExploreFragment`、`ConfigActivity` 的 `FragmentContainerView` | Compose 导航状态和 `ListDetailPaneScaffold`；删除嵌套 Fragment 容器 |
| 键盘工具条与遗留 Adapter 基类 | `KeyboardToolPop`、`RecyclerAdapter`、`DiffRecyclerAdapter` | Compose `Lazy*`、`DropdownMenu`、`ModalBottomSheet`；只在漫画迁移前保留漫画专用实现 |

### 5.2 分批实施

1. **建立 Compose 替代组件**：提供 `LegadoTextEditDialog`、`LegadoCodeEditDialog`、`LegadoToast`、`LegadoSelectActionBar` 和 `LegadoDetailSlider`。组件以状态和回调传递结果，不暴露 `View`、`LayoutInflater` 或资源 ID。
2. **替换通用绑定调用点**：先迁移所有使用 `DialogEditTextBinding` 的规则、书源、导入、搜索和阅读器编辑入口；再迁移 `DialogEditCodeBinding`。删除 `ProgrammaticBindings.kt` 中已无调用方的绑定，直到整个文件可删除。
3. **迁移阅读器周边**：将 `SelectActionBar`、`DetailSeekBar`、`PopupAction`、`TextActionMenu` 和 `KeyboardToolPop` 改为 Compose。该批次不修改漫画阅读器；文字阅读器页面继续沿用既有排版与手势业务逻辑。
4. **移除 Fragment 容器**：将“我的”、发现和设置的双栏页面改为 Compose 状态驱动的详情页，消除 `FragmentContainerView`、子 Fragment 事务和 `AndroidView` 容器。
5. **清理基础设施与依赖**：在非漫画调用方归零后，删除 `androidx.viewbinding.ViewBinding` 兼容接口、`BaseActivity<VB>`/`VMBaseActivity<VB>` 的绑定泛型、viewbinding delegate，以及不再被漫画以外功能引用的 RecyclerView Adapter 基类；最后从 Gradle 中删除无调用的旧 UI 依赖。

### 5.3 验收条件

- 非漫画代码不再引用 `io.legado.app.databinding.ProgrammaticBindings` 或 `androidx.viewbinding.ViewBinding`。
- 非漫画页面不再创建 `FragmentContainerView`，也不通过 `AndroidView` 承载项目自有 Fragment 或普通 View 控件。
- 所有文本编辑、批量操作、滑条、菜单、Toast、规则和书源编辑流程与现有行为一致。
- `res/layout*`、`res/menu` 和偏好设置 XML 仍保持为空；仅 Android 系统元数据 XML 保留。
- 构建和单元测试通过，并对书源编辑、规则编辑、阅读器文本选择、平板双栏和设置导航做真机回归。

### 5.4 2026-10-13 实施记录

- 已完成批次 1、2：统一 Compose 文本/代码输入弹窗已经覆盖规则、书源、导入、搜索、缓存与阅读器入口；`ProgrammaticBindings.kt` 已删除。
- 已完成批次 3 的通用组件：`PopupAction`、`TextActionMenu`、`KeyboardToolPop` 已改为 Compose；`SelectActionBar`、`DetailSeekBar` 已由现有 Compose 操作栏和滑条取代。
- 已完成基础设施收缩：`BaseActivity`/`VMBaseActivity` 不再使用 ViewBinding 泛型；ViewBinding delegate、`RecyclerAdapter`、`DiffRecyclerAdapter` 与相关 ViewHolder 已删除。漫画列表暂时保留自己的 RecyclerView Adapter。
- 已移除所有 `FragmentContainerView` 引用。发现页详情改为进入 Compose 详情页；“关于”和书源网页登录已直接由 Activity 承载 Compose。二维码仍按第三方相机预览例外使用 `AndroidView`。
- 设置中心与“我的”的内容本身已经是 Compose，但仍通过普通 `FrameLayout` 承载现有 Fragment 生命周期，以保留 Activity Result、对话框和偏好监听行为；后续应把这些控制器提升到 Activity/ViewModel 后再删除最后两处自有 Fragment 桥接。
- 验证：`compileAppDebugKotlin`、`assembleAppDebug`、1699 项 `testAppDebugUnitTest` 均通过；Pixel Tablet 模拟器已覆盖首页、“我的”、“关于”、“其它设置”和 Compose 文本输入弹窗，未发现应用崩溃。
