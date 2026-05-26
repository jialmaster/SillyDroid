# server-overlay

## 模块职责

- 放注入上游 Tavern server payload 的覆盖层。
- 这里的内容会随 server source / server payload 进入最终宿主运行环境。

## 主要公开入口 / 核心类

- 当前目录内容本身就是 overlay 入口。
- 需要结合 bootstrap/server 打包链一起理解。

## 上游依赖与下游被谁使用

- 上游由构建链把这里的覆盖内容并入 server 资产。
- 下游由最终 Tavern server payload 在宿主运行时消费。

## 修改时优先放这里的内容边界

- 只放确实属于 server payload 覆盖层的内容。
- 只处理需要随 Tavern 服务端资产一起下发的改动。

## 不该放进来的内容

- Android 宿主 Activity、布局、资源。
- 只在 Web 扩展层生效的前端文件。
- 与 server payload 无关的临时脚本。
