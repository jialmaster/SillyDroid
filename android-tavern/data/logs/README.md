# data/logs

## 模块职责

- 放宿主与运行时日志相关实现。
- 包括日志会话、日志快照、日志导出 ZIP、宿主诊断记录等。

## 主要公开入口 / 核心类

- `HostLogRepositoryImpl`
- `HostLogManager`
- `HostRuntimeLogManager`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`、`data/runtime`、`data/settings`。
- 下游由 `app/AppGraph` 装配给 `SillyDroidApplication`、`feature/main`、`feature/settings` 使用。

## 修改时优先放这里的内容边界

- 宿主日志目录和日志会话管理。
- 启动日志、运行时日志、诊断日志写入。
- 日志导出选项、ZIP 聚合、公共下载目录导出。

## 不该放进来的内容

- 导出前的 UI 弹窗布局和页面交互。
- WebView 下载桥逻辑。
- 设置页或悬浮日志的界面状态管理。
