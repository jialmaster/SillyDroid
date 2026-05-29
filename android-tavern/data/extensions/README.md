# data/extensions

## 模块职责

- 放扩展库存、默认仓库、远端 manifest、安装/重装/清理实现。
- 当前扩展管理的真实后端主要收口在这里。

## 主要公开入口 / 核心类

- `ExtensionsRepositoryImpl`
- `ExtensionsLocalDataSource`
- `RemoteManifestDataSource`
- `ExtensionCommandExecutor`
- `HostExtensionCommandRunner`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`。
- 下游由 `app/AppGraph` 装配给 `feature/settings` 和 `feature/main` 的扩展安装流程使用。

## 修改时优先放这里的内容边界

- 本地扩展目录扫描与库存构建。
- 默认仓库地址、仓库规范化、GitHub 可达性检查。
- 扩展安装、重装、删除、坏目录清理；具体运行时启动方式由 `data/runtime` 的 host runner 负责。

## 不该放进来的内容

- 扩展列表页面 UI。
- Material 对话框与按钮状态切换。
- 与 WebView 宿主桥无关的页面级交互细节。
