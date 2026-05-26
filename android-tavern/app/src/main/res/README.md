# res

## 模块职责

- Android 宿主资源目录。
- 包括布局、文案、颜色、样式、drawable、XML 资源等。

## 主要公开入口 / 核心类

- `layout/`
- `values/`
- `values-night/`
- `drawable/`
- `xml/`

## 上游依赖与下游被谁使用

- 上游由 Android 构建系统直接打包。
- 下游被 `app`、`feature/main`、`feature/settings`、`ui/update` 共同使用。

## 修改时优先放这里的内容边界

- Android 宿主界面的布局资源。
- 字符串、颜色、样式和主题 token。
- FileProvider、network security config 这类 Android XML 资源。

## 不该放进来的内容

- Kotlin/JS 逻辑。
- 业务配置文件和 bootstrap 资产。
- 随地硬编码布局常量后又绕过 token 体系的资源分叉。
