# extensions

## 模块职责

- 放会被打包进 Tavern 的 Android 宿主扩展。
- 当前这里的重点是 `sillydroid-android-host`，负责主题、通知开关、宿主桥设置项等 Web 侧宿主能力。

## 主要公开入口 / 核心类

- `sillydroid-android-host/index.js`
- `sillydroid-android-host/settings.html`
- `sillydroid-android-host/style.css`
- `sillydroid-android-host/themes/glass.css`
- `sillydroid-android-host/manifest.json`

## 上游依赖与下游被谁使用

- 上游由 `app` 打包期任务同步到 bootstrap 资产中。
- 下游在 Tavern 页面中运行，并通过宿主桥与 Android 宿主协作。

## 修改时优先放这里的内容边界

- Tavern 页面内的安卓宿主开关和主题选项。
- Web 侧调用宿主桥的入口。
- 与 Android 宿主主题联动的 CSS 和前端行为。

## 不该放进来的内容

- Android 侧 Activity、Repository、DownloadManager 逻辑。
- 上游 Tavern server overlay 逻辑。
- 与扩展无关的通用前端资源。
