# core/ui

## 模块职责

- 放真正跨页面复用的 UI 控制器与小型共享 UI 组件。
- 当前已经收口的共享 UI 能力主要有系统栏外观、日志导出选择、拖拽滚动条。

## 主要公开入口 / 核心类

- `SystemBarAppearanceController`
- `HostLogExportSelectionDialog`
- `DraggableScrollThumbController`

## 上游依赖与下游被谁使用

- 上游依赖 `core:model`。
- 下游被 `feature/main`、`feature/settings`、`ui/update` 直接复用。

## 修改时优先放这里的内容边界

- 多个页面都会复用、且已经稳定下来的 UI 控制器。
- 不依赖某个具体页面上下文的共享对话框和滚动辅助能力。

## 不该放进来的内容

- 仅主界面或仅设置页使用的页面逻辑。
- 与 WebView、bootstrap、下载业务强绑定的实现。
- 只是“看起来像 UI 工具”但其实只服务单一 feature 的代码。
