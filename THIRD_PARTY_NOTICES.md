# Third-Party Notices

本仓库只整理当前 Android 宿主打包链直接涉及的主要第三方项目与许可证信息。

具体发布产物中实际包含的第三方组件，仍以对应版本的源码、依赖清单、发布包内容与随附许可证文本为准。

## 主要第三方项目

### SillyTavern

- 项目地址：https://github.com/SillyTavern/SillyTavern
- 用途：作为 Android 宿主打包流程中的上游 Tavern server source 与最终 server payload 来源。
- 许可证：AGPL-3.0

### Termux 生态运行时资源

- 项目地址：https://github.com/termux/termux-packages
- 用途：Android 运行时层基于 Termux 生态的 prefix、bootstrap 约定和相关运行时资源构建。
- 许可证：以对应引入组件和发布包内附带的许可证文本为准。

## 分发说明

- 分发 APK、runtime image 或解包后的 payload 时，应同时保留相关上游组件的版权声明与许可证文本。
- 若二次分发包含新增第三方依赖、扩展、模型或资源，应由分发方补充对应的许可证与归属说明。
