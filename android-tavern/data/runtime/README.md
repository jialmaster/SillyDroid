# data/runtime

## 模块职责

- 放 bootstrap/runtime、进程、资产、metadata、console runtime 的具体实现。
- 启动链、rootfs/runtime 资产准备、服务进程启动与健康监控，主要都在这里。

## 主要公开入口 / 核心类

- `BootstrapSessionManager`
- `DefaultHostProcessManager`
- `AssetRuntimeMetadataRepository`
- `BootRuntimeConfigRepository`
- `DefaultConsoleRuntimeRepository`
- `HostProcessManager`
- `HostExtensionDirectoriesProvider`

## 上游依赖与下游被谁使用

- 上游依赖 `domain`。
- 下游主要由 `app/AppGraph` 装配后提供给 `feature/main`、`feature/settings`、`data/logs`。

## 修改时优先放这里的内容边界

- 启动步骤编排。
- rootfs/runtime/server 资产准备。
- 本地进程生命周期、健康探针、自动重启。
- runtime metadata 和 runtime config 的具体读取实现。

## 不该放进来的内容

- Activity / View / Toast 这类 UI 行为。
- 设置页表单逻辑。
- 扩展仓库管理或更新 UI 协调。
