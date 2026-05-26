# app

## 模块职责

- Android 应用壳模块。
- 负责 `Application`、Manifest、`AppGraph` 总装配，以及打包期 bootstrap 资产同步。
- 这是宿主真正的应用入口模块，最终 APK 也是从这里产出。

## 主要公开入口 / 核心类

- `SillyDroidApplication`
- `AppGraph`
- `AndroidManifest.xml`
- `build.gradle.kts` 中的 bootstrap 资产同步任务

## 上游依赖与下游被谁使用

- 上游依赖整个多模块工程：`feature/main`、`feature/settings`、`data/*`、`domain`、`core/*`、`ui/update`。
- 下游由 Android 系统直接启动，并把 `MainActivity`、`BootstrapSettingsActivity` 暴露为宿主入口。

## 修改时优先放这里的内容边界

- 应用级初始化。
- 全局依赖装配。
- Manifest 权限、Activity 注册、FileProvider。
- 打包时需要并入 APK 的 bootstrap 资产同步。

## 不该放进来的内容

- 具体业务逻辑。
- 某个页面自己的 controller/coordinator。
- 能放进 `data`、`domain`、`feature`、`core/ui` 的实现细节。
