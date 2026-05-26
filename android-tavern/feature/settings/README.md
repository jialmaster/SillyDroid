# feature/settings

## 模块职责

- 设置页 feature。
- 负责表单、数据管理、扩展管理、日志、终端、about、更新入口等设置页分区协作。

## 主要公开入口 / 核心类

- `BootstrapSettingsActivity`
- `SettingsActivityViewModel`
- `BootstrapSettingsScreenController`
- `BootstrapSettingsFormController`
- `BootstrapSettingsDataCoordinator`
- `BootstrapSettingsExtensionsCoordinator`
- `BootstrapSettingsLogsCoordinator`
- `BootstrapSettingsSettingsCoordinator`
- `TerminalPageController`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`、`core/ui`、`ui/update`。
- 下游由 `app` 的 `BootstrapSettingsActivity` 直接承载。
- `feature/main` 还会复用这里的默认扩展安装流程。

## 修改时优先放这里的内容边界

- 设置页分区 coordinator。
- 表单与搜索过滤。
- 数据导入导出入口编排。
- 扩展列表与默认扩展安装对话流程。
- 日志浏览与终端页交互。

## 不该放进来的内容

- 更新元数据后端实现。
- 宿主进程、rootfs 资产、日志 ZIP 底层实现。
- 主界面 WebView 宿主逻辑。
