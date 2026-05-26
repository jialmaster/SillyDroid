# bootstrap assets

## 模块职责

- 宿主 bootstrap 运行所需资产目录。
- 当前重点包括 rootfs、bootstrap 脚本、server 资产。

## 主要公开入口 / 核心类

- `rootfs/`
- `scripts/`
- `server/`

## 上游依赖与下游被谁使用

- 上游由构建脚本和 `app` 打包任务生成/同步。
- 下游由 runtime/bootstrap 启动链消费。

## 修改时优先放这里的内容边界

- 启动时必须从 APK 解出或读取的 bootstrap 资产。
- 与 rootfs/runtime/server 初始化直接相关的静态内容。

## 不该放进来的内容

- Android 资源。
- 仅供开发机本地调试、不会进入 APK 的文件。
- 脱离 bootstrap 语义的零散脚本。
