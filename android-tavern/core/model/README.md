# core/model

## 模块职责

- 放跨模块共享的数据模型、枚举、导航契约。
- 当前重点包括 bootstrap step、settings navigation、update model、日志模型、扩展模型。

## 主要公开入口 / 核心类

- `core/model/bootstrap/BootstrapModels.kt`
- `core/model/settings/SettingsNavigationContract.kt`
- `core/model/settings/HostDisplayMode.kt`
- `core/model/update/AppUpdateModels.kt`
- `core/model/logs/HostLogModels.kt`
- `core/model/extensions/ExtensionModels.kt`

## 上游依赖与下游被谁使用

- 上游只依赖基础 Kotlin/Android 类型。
- 下游被 `domain` 直接依赖，再向 `data`、`feature`、`ui` 全链路共享。

## 修改时优先放这里的内容边界

- 需要跨模块共享的稳定模型。
- bootstrap 状态、步骤、事件流的共享语义。
- settings 和 Activity 之间的导航契约。

## 不该放进来的内容

- 具体 Android 实现。
- repository/controller 逻辑。
- 只在单个页面内部使用的临时 UI 状态类。
