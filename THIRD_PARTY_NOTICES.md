# Third-Party Notices

本仓库项目自有代码按 `LICENSE` 中声明的 PolyForm Noncommercial License 1.0.0 授权；本文件只整理当前 Android 宿主打包链直接涉及的主要第三方项目与许可证信息。

具体发布产物中实际包含的第三方组件，仍以对应版本的源码、依赖清单、发布包内容与随附许可证文本为准。

## 主要第三方项目

### SillyTavern

- 项目地址：https://github.com/SillyTavern/SillyTavern
- 用途：作为 Android 宿主打包流程中的上游 Tavern server source 与最终 server payload 来源。
- 许可证：AGPL-3.0

### Termux 生态运行时资源

- 项目地址：https://github.com/termux/termux-packages
- 用途：Android 运行时层基于 Termux 生态的 prefix、bootstrap 约定和相关运行时资源构建；当前构建链路通过解析 Termux Packages 索引下载并解包对应 `.deb` 运行时包。
- 许可证：以对应引入组件和发布包内附带的许可证文本为准。

### Termux terminal-view / terminal-emulator

- 项目地址：https://github.com/termux/termux-app
- 用途：设置页终端复用 Termux terminal-view / terminal-emulator 组件，避免宿主自行重写 ANSI/PTY 渲染与交互栈。
- 引入方式：Gradle 依赖 `com.termux.termux-app:terminal-view:0.118.0`。
- 许可证：以上游组件对应版本的许可证、例外条款和文件级声明为准。

## 分发说明

- 分发 APK、runtime image 或解包后的 payload 时，应同时保留相关上游组件的版权声明与许可证文本。
- 若二次分发包含新增第三方依赖、扩展、模型或资源，应由分发方补充对应的许可证与归属说明。
- `LICENSE` 中的 PolyForm Noncommercial License 1.0.0 只约束本仓库项目自有内容，不改变第三方组件的原始授权，也不向第三方组件增加额外限制。
