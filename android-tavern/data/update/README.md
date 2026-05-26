# data/update

## 模块职责

- 放应用内更新的后端实现。
- 包括更新元数据抓取、下载状态、SHA 校验、安装前状态持久化。

## 主要公开入口 / 核心类

- `AppUpdateRepositoryImpl`
- `AppUpdateStateStore`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`。
- 下游由 `app/AppGraph` 装配给 `ui/update/AppUpdateCoordinator` 使用。

## 修改时优先放这里的内容边界

- 最新版本元数据读取。
- `DownloadManager` 下载状态查询。
- APK SHA-256 校验。
- 更新下载记录和安装前状态持久化。

## 不该放进来的内容

- 页面按钮、角标、Toast、安装器拉起时机这类 UI 协调。
- 与 bootstrap/runtime 无关的业务逻辑。
