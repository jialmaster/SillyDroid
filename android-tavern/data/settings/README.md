# data/settings

## 模块职责

- 放 Tavern 配置读写与数据导入导出实现。
- 配置文件结构、备份 ZIP、导入识别规则和落盘逻辑都在这里。

## 主要公开入口 / 核心类

- `TavernConfigRepository`
- `TavernDataArchiveManager`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`。
- 下游由 `app/AppGraph` 提供给 `feature/settings` 使用。
- `data/logs` 也依赖这里的一部分路径/配置语义。

## 修改时优先放这里的内容边界

- Tavern 配置文件读写。
- 导入导出 ZIP 布局识别。
- 宿主管理目录与上游/第三方数据结构之间的映射。

## 不该放进来的内容

- 页面表单渲染。
- Activity 跳转和 finish 逻辑。
- 扩展安装命令、下载状态机、日志显示控件。
