# assets

## 模块职责

- APK 内置静态资产入口。
- 这里承载会直接打进应用包、供 bootstrap/runtime/server 初始化消费的宿主资产。

## 主要公开入口 / 核心类

- `bootstrap/`

## 上游依赖与下游被谁使用

- 上游由 `app` 模块打包进入 APK。
- 下游由宿主启动链和 bootstrap/runtime 初始化过程读取。

## 修改时优先放这里的内容边界

- 需要随 APK 一起分发、并由宿主离线读取的静态资产。
- bootstrap、runtime、server 初始化要消费的固定目录结构。

## 不该放进来的内容

- 只在构建机临时使用的中间产物。
- 应该放进 `res` 的 Android 资源。
- 与 bootstrap/runtime/server 无关的杂项文件。
