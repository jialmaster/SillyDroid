# android-tavern

## 这是什么

`android-tavern` 是 SillyDroid 的 Tavern Android 宿主工程。

这里负责把 bootstrap/runtime、WebView 宿主能力、设置页、更新、日志、扩展管理这些链路串起来，并最终打包成 Android 应用。

这个目录是“宿主工程导航入口”，不是用户使用说明。后续改代码前，建议先看这里，再进入对应模块目录的 `README.md`。

## 统一入口

### `SillyDroidApplication`

- 应用级启动入口。
- 负责初始化 `AppGraph`。
- 负责应用级日志、崩溃捕获、Activity 生命周期诊断。

### `AppGraph`

- 宿主依赖装配入口。
- 统一创建 repository、controller、coordinator 所需的实现。
- 当前宿主的模块协作关系，优先从这里看“谁依赖谁”。

### `MainActivity`

- 主界面装配入口。
- 现在只负责把几个 host 接起来，不再自己承载大块业务逻辑。
- 当前主链路主要由这些 host 组成：
  - `TavernWebViewHost`
  - `BootstrapOverlayHost`
  - `FloatingLogsHost`
  - `HostIoController`
  - `SystemBarInsetsController`

### `BootstrapSettingsActivity`

- 设置页装配入口。
- 负责把表单、数据迁移、扩展管理、日志、终端、更新等 coordinator 接起来。
- 设置页相关改动，优先顺着这里找到对应 coordinator 再进入子模块。

## 已统一收口的共通能力

### 启动 step 与会话状态

- 共享模型在 `core/model/bootstrap`。
- 抽象控制入口是 `BootstrapController`。
- 具体启动会话编排在 `BootstrapSessionManager`。
- 启动步骤、状态、失败信息、事件流，优先沿这条链查，不要在页面里各自拼一套状态语义。

### 下载链

- `HomeWebViewController` 是 WebView 下载监听的主入口。
- 普通 URL 下载走 `BrowserDownloadController`。
- `blob:` / `data:` 下载走 `BlobDownloadController` 和 `AndroidBlobDownloadBridge`。
- 宿主下载相关 launcher、权限、通知、文件选择器装配统一收口在 `HostIoController`。
- 当前目标语义是统一落系统 `Download`，不要在主界面、设置页再各自发散一套保存路径。

### 系统通知链

- Web 侧桥接入口是 `AndroidSystemNotificationBridge`。
- Android 宿主通知通道与发送控制在 `SystemNotificationController`。
- 通知权限申请也挂在 `HostIoController`。

### 系统栏与宿主主题同步

- Android 宿主系统栏外观统一用 `SystemBarAppearanceController`。
- WebView 页面背景到系统栏颜色同步，统一收口在 `TavernWebViewHost`。
- Tavern 页面内的安卓宿主主题、通知、宿主设置项，统一由 `extensions/sillydroid-android-host` 负责。

### 日志导出选择

- 日志导出前的类型勾选对话框统一用 `HostLogExportSelectionDialog`。
- 实际日志读取、聚合、导出 ZIP 由 `HostLogRepository` 及其实现负责。
- 设置页日志导出和悬浮日志导出是两个入口，但当前共用同一套日志类型选择和导出后端。

### 更新链

- 更新元数据抓取、下载状态、SHA 校验、安装前状态持久化在 `AppUpdateRepositoryImpl`。
- 页面侧更新按钮、角标、下载完成后拉起安装器的协调在 `AppUpdateCoordinator`。
- 主界面 overlay 与设置页 about 区共用这条更新链。

### 扩展管理链

- 扩展库存、默认仓库、远端 manifest、安装/重装/清理在 `ExtensionsRepositoryImpl`。
- 设置页和主界面首次引导里，扩展安装流程都复用同一套实现链。

### 数据导入导出链

- Tavern 配置和数据 ZIP 导入导出统一走 `TavernDataArchiveManager`。
- 后续涉及数据迁移、备份结构、导入识别规则时，优先改这一层，不要在 Activity 里直接拼 ZIP 规则。

## 目前还不是全局统一层的能力

### 消息提示

- 当前消息提示仍主要分布在各自页面和 coordinator 内部，常见形式是 `Toast` 或页面级 `showMessage(...)`。
- 例如设置页有 `BootstrapSettingsScreenController.showMessage(...)` 这类页面级封装。
- 但当前没有单一全局 message center，也没有统一总线。
- 后续如果只是改现有页面提示，优先沿当前 feature/coordinator 的局部封装继续收敛，不要在 README 基础上误判为已经有全局提示层。

## 模块导航

### `app`

- Android 应用壳、Manifest、`Application`、`AppGraph`、打包期 bootstrap 资产同步。
- 需要看应用装配关系或打包入口时先看这里。

### `core/common`

- 纯基础公共能力。
- 当前主要放 dispatcher 这类不带业务语义的共通基础件。

### `core/model`

- 跨模块共享模型、枚举、导航契约。
- 包括 bootstrap step、settings navigation、update model 等。

### `core/ui`

- 真正跨页面复用的 UI 控制器。
- 当前已有系统栏外观、日志导出选择、拖拽滚动条等。

### `domain`

- 抽象接口与应用图契约层。
- 这里定义“要做什么”，不放 Android 细节实现。

### `data/runtime`

- bootstrap/runtime、进程、资产、metadata、console runtime 的具体实现。
- 启动、进程、rootfs/runtime 相关问题优先从这里排。

### `data/settings`

- Tavern 配置读写与数据导入导出。
- 配置文件结构、备份 ZIP、导入识别规则都在这里。

### `data/logs`

- 宿主与运行时日志、日志快照、日志导出 ZIP。

### `data/extensions`

- 扩展清单、默认仓库、远端 manifest、安装/重装/清理。

### `data/update`

- 更新元数据抓取、下载状态、校验、安装前状态持久化。

### `feature/main`

- 主界面、WebView 宿主、下载桥、通知桥、悬浮日志、bootstrap overlay。

### `feature/settings`

- 设置页各分区 coordinator、表单、终端、扩展、日志、数据管理。

### `ui/update`

- 更新 UI 协调层。
- 当前可复用于主界面 overlay 和设置页 about 区。

### `extensions`

- 打包进 Tavern 的 Android 宿主扩展。
- 负责主题、通知开关、宿主桥设置项。

### `server-overlay`

- 注入上游 Tavern server payload 的覆盖层。

### `app/src/main/assets`

- APK 内置静态资产入口。
- 包含 bootstrap/runtime/server 等资产。

### `app/src/main/assets/bootstrap`

- bootstrap 运行所需 rootfs、脚本、server 资产。

### `app/src/main/res`

- Android 宿主资源目录。
- 布局、文案、主题 token 优先在这里统一维护。

### `app/src/main/jniLibs`

- APK 打包携带的 native libs。
- 这里只放宿主运行期需要随 APK 带上的本地库。

## 修改时的总边界

- 先顺着入口找真实链路，再决定改哪个模块。
- 共通语义优先收敛到已有 shared 层，不要在 `feature` 里重复发明。
- 当前没有全局 message center，不要把 README 当作已有实现能力。
- runtime/bootstrap 资产与阶段边界仍以仓库根 README 和 `scripts` 里的 contract 注释为准。
