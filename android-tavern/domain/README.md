# domain

## 模块职责

- 抽象接口与应用图契约层。
- 这里定义宿主需要什么能力，不直接放 Android 细节实现。

## 主要公开入口 / 核心类

- `domain/app/SillyDroidAppGraph.kt`
- `domain/bootstrap/BootstrapController.kt`
- `domain/bootstrap/RuntimeConfigRepository.kt`
- `domain/bootstrap/RuntimeMetadataRepository.kt`
- `domain/logs/HostLogRepository.kt`
- `domain/extensions/ExtensionsRepository.kt`
- `domain/settings/DataArchiveRepository.kt`
- `domain/update/AppUpdateRepository.kt`

## 上游依赖与下游被谁使用

- 上游依赖 `core/common` 和 `core/model`。
- 下游被 `data/*` 提供实现，被 `app`、`feature/*`、`ui/update` 消费。

## 修改时优先放这里的内容边界

- 抽象能力接口。
- AppGraph 暴露给上层的稳定依赖入口。
- 各模块之间共享的能力边界定义。

## 不该放进来的内容

- Android `Context`、`DownloadManager`、`WebView` 等细节实现。
- 具体 ZIP 结构、文件路径、下载逻辑、UI 协调逻辑。
