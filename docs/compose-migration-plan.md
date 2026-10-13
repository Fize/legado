# Legado Android 全量现代化一次性切换计划

> 状态：待执行
>
> 制定日期：2026-10-13
>
> 交付方式：单一集成分支完成全部改造，通过全部验收后一次性合入和发布。主线不接收半迁移状态，不保留旧 UI/新 UI 双轨、兼容开关或渐进式路由。

## 1. 目标与完成定义

本计划将现有应用一次性切换到 Android 当前推荐的最终形态：

- Jetpack Compose + Material 3 负责全部应用 UI。
- 一个 UI Activity 承载整个应用；使用 Navigation 3 的类型安全 back stack、深链和 adaptive scenes。
- 屏幕级 `ViewModel` 暴露不可变 `StateFlow<UiState>`；Compose 使用 `collectAsStateWithLifecycle()`；用户操作通过明确的方法进入 ViewModel。
- Repository 作为应用数据的唯一入口；Room、DataStore、网络、文件、媒体和系统能力不直接暴露给 UI。
- 使用 `SavedStateHandle` 保存恢复屏幕所需的最小 key；复杂状态从数据层重新构建。
- Material 3 Adaptive 覆盖手机、平板、折叠屏、桌面窗口、横竖屏和多窗口。
- Edge-to-edge、预测性返回、无障碍、键盘/鼠标/D-pad、进程死亡恢复和状态连续性成为发布门槛。
- 删除 Fragment、DialogFragment、ViewBinding、RecyclerView/ViewPager UI、Preference XML、菜单 XML、项目自有 View 桥接和旧 UI 基础设施。
- 仅允许 WebView、视频 Surface、相机预览、Sora 代码编辑器等没有 Compose 等价物的底层原生组件通过集中封装的 `AndroidView` 使用。

“完成”同时要求功能等价、架构收口、旧代码删除、自动化测试通过和目标设备实测通过。只完成 Compose 外观或只删除 XML 不算完成。

## 2. 当前基线

截至 2026-10-13 的仓库扫描结果：

| 项目 | 当前状态 | 最终要求 |
| --- | ---: | ---: |
| Manifest UI Activity | 46 | 1 个 `AppActivity`；启动图标使用 `activity-alias` |
| Fragment 类 | 约 96 | 0 |
| DialogFragment/BaseDialogFragment 使用文件 | 129 | 0 |
| 包含 `AndroidView` 的文件 | 28 | 只存在于批准的原生能力适配器目录 |
| UI 层 AndroidViewModel/BaseViewModel(Application) | 46 | 0 |
| UI 层事件总线相关文件 | 57 | 0 |
| RecyclerView/ListAdapter 相关文件 | 65 | 0（非 UI 基础库间接依赖除外） |
| `res/layout*` 与 `res/menu` UI 文件 | 0 | 0 |
| `res/xml` | 5 个系统元数据文件 | 保留系统要求的 5 个文件 |
| Android instrumentation 测试文件 | 362 | 全部可编译、可运行 |

当前 Compose 化工作可以作为业务行为参考，但不能被视为最终架构基线。尤其包括：

- `MainActivity` 仍通过 `AndroidView(ViewPager)` 和 `FragmentStatePagerAdapter` 承载根页面。
- 漫画阅读器已迁移渲染层，但状态仍主要存放在 Activity，设置仍使用 DialogFragment，横向“关闭吸附”配置尚未进入 Pager 行为。
- 大量页面已把 XML 换成动态 View 或 Compose 外壳中的 AndroidView；这些都要在本次切换中收口。
- 完整 `androidTest` 仍包含迁移前 API，当前无法作为发布门槛稳定运行。

## 3. 不可变架构决策

### 3.1 应用壳与导航

1. 新建唯一 UI 入口 `AppActivity : ComponentActivity`，只负责：
   - `enableEdgeToEdge()`、窗口与系统栏策略；
   - `setContent { LegadoApp() }`；
   - 外部 Intent 转换为类型安全路由；
   - Activity Result、权限和必须依赖 Activity 的系统能力代理。
2. 使用 Navigation 3：
   - 所有路由是 `@Serializable sealed interface AppRoute`；参数只传 ID、URL 或稳定 key，不传数据库实体和大对象。
   - 根 back stack 是导航的单一事实来源，支持保存恢复、深链、通知、快捷方式、分享、文件关联和 `PROCESS_TEXT`。
   - 返回行为统一交给 Navigation 3 和预测性返回，不再手写 Fragment back stack。
3. 启动器变体改为 `<activity-alias>` 指向 `AppActivity`。现有详情、设置、搜索、编辑器、登录、扫码、播放器和阅读器 Activity 全部变成 destination。
4. 广播接收器、Service、ContentProvider 保留为 Android 组件，但不得承载应用 UI 或自行维护导航状态。

### 3.2 Adaptive 导航与窗口布局

- 根导航使用 `NavigationSuiteScaffold` 或 Navigation 3 对应 scene：紧凑窗口显示底栏，中等/展开窗口显示 rail 或 drawer。
- 列表—详情关系使用 Navigation 3 `SceneStrategy` 和 Material adaptive-navigation3；不建立另一套 pane 内部导航。
- 书架、发现、目录、书源、规则、书签、记录、文件与设置都使用同一类型安全 back stack，在大屏中由 scene 同时呈现多个 destination。
- 阅读器、视频、扫码和沉浸式编辑器保持全屏 scene；窗口改变时保存当前内容 key、选中项、滚动/页码和编辑草稿。
- 所有布局按当前窗口尺寸和 posture 决策，不读取物理设备类型，也不依赖固定 `sw600dp` 分支。

### 3.3 状态、事件和依赖

每个屏幕采用以下固定边界：

```text
Composable Screen
  └─ collectAsStateWithLifecycle(uiState)
       └─ ScreenViewModel : ViewModel
            ├─ StateFlow<ScreenUiState>
            ├─ user action methods
            └─ repositories / use cases
                 ├─ Room
                 ├─ Proto DataStore
                 ├─ network
                 ├─ file/document storage
                 └─ media/system gateways
```

- 禁止 Activity、Composable、ViewModel 直接访问全局数据库对象、SharedPreferences、文件系统或网络客户端。
- 禁止 ViewModel 持有 `Activity`、`Context`、`Resources`、View 或 Fragment；需要平台能力时注入纯接口。
- 禁止用一次性 Channel/SharedFlow 通知 UI 显示结果。操作结果写入 `UiState`，消费后由明确 action 清除。
- 导航由 UI 根据已确认状态执行，不能把 NavController 注入 ViewModel。
- 可复用复杂组件使用普通 state holder；屏幕业务使用 ViewModel。
- 引入 Hilt 管理 Activity、ViewModel、Repository、数据库、DataStore 和系统 gateway 的依赖。
- `BaseViewModel(Application)`、全局 EventBus 和屏幕可变单例全部移除。

### 3.4 数据与持久化

- Room 继续作为结构化业务数据来源，DAO 只由 Repository 访问。
- AppConfig/SharedPreferences 迁移为版本化 Proto DataStore；提供一次性旧配置导入，导入成功后删除旧 key 和兼容读取路径。
- `ReadBook`、`ReadManga` 等全局会话改成 reader repository/session store，按 book ID 创建可恢复会话。
- 编辑草稿、阅读位置、列表筛选和导航 key 明确划分：
  - 可长期保存：Room/DataStore；
  - 进程恢复所需的最小状态：`SavedStateHandle`；
  - 纯视觉瞬时状态：`rememberSaveable` 或组件 state holder。
- 文件导入使用 Storage Access Framework 与持久 URI 权限；导出使用 SAF/MediaStore。重新审核 `MANAGE_EXTERNAL_STORAGE` 与旧存储权限，只有核心能力和分发政策均要求时才保留。

### 3.5 UI 与原生组件边界

- Material 3 主题、Typography、Shape、Spacing、Elevation、motion 和 icon 语义集中在 design system。
- 图片加载由单一 Compose `LegadoAsyncImage` 入口处理；移除以 ImageView/CoverImageView 为目的的 AndroidView。
- Markdown、富文本、badge、颜色预览、封面组合和普通输入控件全部使用 Compose。
- `AndroidView` 只允许出现在 `ui/platformadapter` 中：
  - `WebViewAdapter`
  - `VideoSurfaceAdapter`
  - `CameraPreviewAdapter`
  - `SoraEditorAdapter`
  - 经性能证明 Compose Canvas 无法替代的文字阅读排版 surface
- 每个适配器必须实现创建、更新、释放、生命周期、保存恢复、无障碍和测试接口；业务页面不得直接创建 View。
- 图片库必须使用稳定 Compose API。实验版 Glide Compose 不作为最终依赖；在切换日选择已验证的最新稳定实现并通过缓存、鉴权、SVG/AVIF、进度和错误重试测试。

## 4. 一次性交付工作包

这些是同一交付中的并行工作包，不是可单独发布的迁移阶段。任意工作包未达到退出条件，整个切换不得合入。

### A. 构建基座与质量门禁

- 在实施日从 Google 官方 stable channel 锁定 Compose BOM、Navigation 3、Lifecycle、Activity、Material 3 Adaptive、Room、DataStore、Hilt、WorkManager 和测试库版本。
- 不使用 alpha/beta 依赖承担核心路径；确需预览 API 时必须有稳定替代方案，否则推迟对应 API 而不是发布实验核心。
- 引入 Kotlin serialization、Navigation 3、adaptive-navigation3、Hilt、DataStore、Compose UI test、Macrobenchmark 和 Baseline Profile。
- CI 建立编译、单测、instrumentation、lint、release/R8、baseline profile、截图和无障碍门禁。

### B. 单 Activity 与 Navigation 3

- 建立完整 Route Catalog，覆盖现有 46 个 Activity 的入口、参数、返回结果、深链和任务栈语义。
- 将所有 `startActivity*`、FragmentTransaction 和 ViewPager tab 导航替换为类型安全路由。
- 统一处理书籍链接、书源导入、规则导入、文件打开、分享、文本处理、通知、桌面快捷方式和三星 S Pen remote action。
- 删除旧 Activity/Fragment 类和 Manifest 声明；启动图标变体改用 alias。

### C. 数据层、配置和屏幕状态

- 按书架、发现、搜索、书籍、阅读、书源、规则、设置、同步、媒体拆分 Repository。
- 将所有 screen state 从 Activity/Fragment 字段提升到 ViewModel `StateFlow`。
- 替换 LiveEventBus/global mutable state；跨模块变化通过 Repository Flow 传播。
- 完成 Proto DataStore schema、旧配置一次性导入、失败恢复和迁移测试。
- 统一错误模型、加载模型、重试、Snackbar/对话框状态和用户可恢复操作。

### D. 根壳与通用设计系统

- `LegadoApp`、主题、NavigationSuite、顶栏、搜索栏、选择操作栏、列表/网格、空态、错误态、加载态、对话框、BottomSheet、Snackbar、图片和表单组件一次完成。
- 所有组件覆盖动态颜色、深浅色、墨水屏、高对比度、大字体、RTL、触摸目标和 reduced motion。
- 删除项目自有普通 View、ViewBinding、Adapter/ViewHolder 和 DialogFragment 基类。

### E. 全业务屏幕重写

按以下领域完整重写，任何领域不保留旧宿主：

1. **启动与主壳**：欢迎/迁移、书架、发现、我的、根导航、快捷入口。
2. **书架与书籍**：全部书架样式、分组、批量整理、本地导入、书籍详情、编辑、目录、书签、记录、缓存与导出。
3. **搜索与发现**：搜索建议、历史、并发结果、发现分类、列表—详情大屏 scene。
4. **书源生态**：管理、分组、订阅、编辑、调试、登录、换源、导入导出、二维码和 JS/规则编辑器。
5. **规则与工具**：替换、高亮、字典、TXT 目录、文件管理、WebDAV、备份恢复、日志、关于与更新。
6. **设置中心**：全部设置、主题、封面、阅读、朗读、备份、其他配置，使用 typed settings 和 adaptive scene。
7. **音视频与朗读**：播放器 destination、Media3 session、通知、音频焦点、耳机/蓝牙、后台与画中画；只有解码 surface 使用 AndroidView。
8. **文字阅读器**：排版模型与 Compose Canvas、选择/批注、搜索、朗读、脚注、图片、翻页动画、双页、墨水屏和进程恢复。
9. **漫画阅读器**：
   - ViewModel `StateFlow<MangaReaderUiState>` 与 sealed `MangaReaderItem`；
   - 移除 Activity 内屏幕状态和命令 Channel；
   - 修复横向关闭分页吸附、缩放边界交接和自动翻页互斥；
   - 设置、滤镜、换源全部改成 Compose destination/dialog/sheet；
   - 保存页码、章节、模式和必要缩放 key，支持进程死亡恢复；
   - 替换实验图片 API，补齐 semantics、键盘和无障碍 action。

### F. 系统能力与后台任务

- 可延迟的备份、同步、检查和清理工作使用 WorkManager，并声明约束、重试和唯一任务策略。
- 播放、下载和朗读等用户可感知长期任务使用符合 target SDK 36 要求的前台服务类型与通知。
- 权限按使用时请求；通知、媒体、文件、相机和后台能力分别建立 gateway 和测试替身。
- 校验 app links、分享、文件关联、通知 PendingIntent、快捷方式和进程冷启动路由。

### G. 遗留删除与依赖收口

最终一次性删除：

- 全部 UI Activity（除 `AppActivity`）、Fragment、DialogFragment、PreferenceFragment。
- `BaseActivity`/`VMBaseActivity` 的旧 UI 职责、`BaseViewModel(Application)`、Fragment/Activity adapter。
- RecyclerView/ViewPager/ViewPager2、ViewBinding、ConstraintLayout、SwipeRefreshLayout、Flexbox 和 color picker 的直接依赖及相关基础类；若第三方库间接依赖，不在业务代码暴露。
- `androidx.preference`、Fragment UI 依赖、旧导航与事件总线依赖。
- 普通控件 AndroidView、动态 View 构造器、废弃资源 ID、旧主题桥和无引用资源。
- 迁移期文档、过时测试 helper 和所有双轨兼容代码。

## 5. 测试与验收矩阵

### 5.1 自动化门禁

必须全部通过：

```bash
./gradlew clean
./gradlew testAppDebugUnitTest
./gradlew compileAppDebugAndroidTestKotlin
./gradlew connectedAppDebugAndroidTest
./gradlew lintAppDebug
./gradlew assembleAppDebug
./gradlew assembleAppRelease
```

并增加以下覆盖：

- 每个 ViewModel 的状态转换、错误、重试和进程恢复测试。
- 每个 route、深链、外部 Intent、返回结果和预测性返回测试。
- Room migration、Proto DataStore migration、备份恢复兼容测试。
- Compose semantics 测试，禁止依赖旧 view ID。
- 关键页面多尺寸截图测试：compact、medium、expanded、横竖屏、折叠 posture、大字体、RTL、深色、墨水屏。
- 文字/漫画阅读器的页码、旋转、进程死亡、双页、RTL、缩放、自动翻页、预取和失败恢复测试。
- Macrobenchmark 覆盖冷启动、首页滚动、搜索、打开书籍、进入两种阅读器；生成 Baseline Profile。

### 5.2 设备实测

- API 26 最低版本手机。
- API 36 手机：手势导航、三键导航、深浅色、预测性返回。
- Pixel Tablet 或同级 expanded 窗口：横竖屏、键盘、鼠标、D-pad、多窗口和窗口连续缩放。
- 折叠屏：折叠、展开、桌面姿态与铰链遮挡。
- 低内存流程：后台杀进程后从最近任务、通知、深链恢复。
- 墨水屏模式：静态刷新、灰阶、动画关闭和高对比度。

关键业务回归必须覆盖：首次启动、升级迁移、书源导入与登录、搜索、加入书架、本地书导入、文字阅读、漫画阅读、朗读、音视频、下载缓存、备份恢复、WebDAV、文件关联和分享。

### 5.3 静态退出条件

以下扫描结果必须满足：

| 检查项 | 要求 |
| --- | ---: |
| `Fragment` / `DialogFragment` / FragmentTransaction | 0 |
| `RecyclerView` / ViewPager / ViewBinding 业务引用 | 0 |
| `AndroidViewModel` / ViewModel 持有 Context | 0 |
| UI 层 EventBus / 全局可变 reader session | 0 |
| Activity 内屏幕级 `mutableStateOf` | 0 |
| UI `res/layout*` / `res/menu` / preference XML | 0 |
| 未在 allowlist 的 `AndroidView` | 0 |
| 缺少 label/action 的可交互 semantics | 0 |
| instrumentation 编译错误、失败或忽略的关键用例 | 0 |

## 6. 原子切换执行规则

1. 从当前确认过的 commit 创建专用集成分支，冻结功能新增，只接受迁移阻断修复。
2. 先建立 Route Catalog、功能等价矩阵、数据库/配置备份样本和性能基线，作为后续对照。
3. 所有工作包都在该分支完成；内部提交按架构、领域和删除工作拆分，便于审查和定位，但不单独发布。
4. 合并前执行一次全量旧代码删除，禁止用“暂时保留”“后续清理”通过评审。
5. 在干净 checkout 上执行完整自动化门禁和设备矩阵，结果与功能等价矩阵逐项签收。
6. 通过后以一个发布版本合入主线。出现阻断问题时整体回退到切换前版本，不在生产环境启用旧/新 UI 双轨。

## 7. 计划交付物

- `RouteCatalog.md`：旧入口到 Navigation 3 route、参数、深链和返回结果映射。
- `FeatureParityMatrix.md`：所有用户功能、旧实现位置、新 destination、测试编号和验收状态。
- `PlatformAdapterAllowlist.md`：允许的 AndroidView、原因、生命周期和测试证明。
- Proto DataStore schema、迁移器和迁移报告。
- 架构依赖图、模块边界和 Repository 接口清单。
- 自动化测试报告、截图矩阵、无障碍报告、性能报告与 Baseline Profile。
- 遗留删除报告：文件、依赖、Manifest 组件和资源扫描结果。

只有上述交付物齐全、所有退出条件为零且设备矩阵通过，才能将全量现代化标记为完成。
