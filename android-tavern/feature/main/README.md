# feature/main

## 模块职责

- 主界面 feature。
- 负责 WebView 宿主、下载桥、通知桥、悬浮日志、bootstrap overlay、系统栏联动等主界面链路。

## 主要公开入口 / 核心类

- `MainActivity`
- `TavernWebViewHost`
- `HomeWebViewController`
- `HostIoController`
- `BrowserDownloadController`
- `BlobDownloadController`
- `AndroidBlobDownloadBridge`
- `AndroidSystemNotificationBridge`
- `FloatingLogsHost`
- `BootstrapOverlayHost`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`、`core/ui`、`feature/settings`、`ui/update`。
- 下游由 `app` 的 `MainActivity` 直接承载。

## 修改时优先放这里的内容边界

- 主界面 Activity 装配。
- WebView 生命周期与恢复。
- 主界面下载、通知、宿主 JS 桥。
- 主界面悬浮日志与 bootstrap overlay 的页面逻辑。

## 不该放进来的内容

- 设置页表单、扩展列表、终端页逻辑。
- 更新元数据抓取实现。
- bootstrap/runtime 进程细节实现本体。
