# SillyDroid Android

[![APK Action](https://img.shields.io/github/actions/workflow/status/jialmaster/SillyDroid/sillydroid-upstream-apk.yml?label=SillyDroid%20APK)](https://github.com/jialmaster/SillyDroid/actions/workflows/sillydroid-upstream-apk.yml)
[![Runtime Release Tag](https://img.shields.io/badge/Runtime%20Release%20Tag-tavern--runtime--linux--arm64-2563eb)](https://github.com/jialmaster/SillyDroid/releases/tag/tavern-runtime-linux-arm64)
[![Latest Release](https://img.shields.io/github/v/release/jialmaster/SillyDroid?display_name=tag&label=SillyDroid%20Version)](https://github.com/jialmaster/SillyDroid/releases)
[![Release Time](https://img.shields.io/github/release-date/jialmaster/SillyDroid?label=SillyDroid%20Release%20Time)](https://github.com/jialmaster/SillyDroid/releases)

这个仓库维护 SillyDroid Android 宿主、离线运行时打包链，以及基于指定 SillyTavern tag 的 APK 构建与发布流程。

上游 SillyTavern 源码不会长期作为主工程保存在仓库里；构建时会同步指定 tag，先生成 stage 3 的 server source，再由 stage 4 组合宿主使用的最终 server payload。

README 保留常用说明；更细的构建语义、阶段边界和脚本职责，以 scripts 下对应脚本开头的 Contract 注释为准。

## 这是啥

- SillyDroid 是一个把 Linux 运行环境、SillyTavern 和 WebView 打包进单个 APK 的项目；不用额外安装 Termux，也不需要手动敲命令，下载安装后即可运行。
- 首次启动会自动解压运行环境，通常约 1 分钟，具体取决于机型和存储性能。
- 应用内置 WebView，对外部浏览器依赖较低，流式聊天和小窗场景更稳定。
- 项目当前大量实现由 AI 协助完成，作者主要按实际使用需求持续整理和迭代。

## 功能介绍

### 可选扩展

- 首次启动时会提示安装可选扩展，安装过程需要设备可访问 GitHub。
- 当前常用扩展包括：酒馆助手、小白 X、提示词模板。

### 内置安卓宿主扩展

- 实时日志：查看酒馆运行日志，排查问题时不需要再额外抓日志。
- 跳转宿主设置：一键打开 APP 原生设置页。
- 新消息通知：酒馆收到新消息后可发送通知栏提醒，部分机型上可能存在不稳定情况。
- 下拉刷新开关：可关闭 WebView 下拉刷新，避免与悬浮小手机、菜单图标等交互冲突。
- 刷新按钮：不使用下拉刷新时，可直接通过按钮手动刷新。

### 宿主设置页

- 基础设置：可视化配置 SillyTavern 基础选项，例如端口、接口等。
- 扩展管理：安装、删除扩展，并支持批量删除。
- 数据迁移：导入、导出完整酒馆数据，包括角色卡、对话、配置和扩展。

## 从 Termux 迁移数据

之前如果在 Termux 里跑 SillyTavern，可以按下面步骤一键迁移：

1. 在 Termux 中运行下面的命令，把数据打包成 ZIP：

```bash
curl -fsSL https://raw.githubusercontent.com/jialmaster/SillyDroid/master/scripts/export-tavern-data.sh | bash
```

> 脚本会自动尝试找到 SillyTavern 目录，打包后保存到手机下载目录；如果缺少 `zip` 命令，也会自动尝试安装。

2. 打开 SillyDroid，等待环境解压完成后，点击右上角进入导入入口。
3. 选择第一步生成的 ZIP 文件并确认导入。
4. APP 会自动替换数据并重启服务，迁移完成。

> 迁移内容包含配置、角色卡与对话、插件和扩展配置。

更细的数据包结构、导入识别规则和扩展目录映射说明见下方“进阶说明”。

## 下载

- Wiki：<https://github.com/jialmaster/SillyDroid/wiki>
- 官网下载页 / 最新状态：<https://jialmaster.github.io/SillyDroid/download>
- Release 列表 / 源码仓库：<https://github.com/jialmaster/SillyDroid/releases>
- 安装说明：下载 `.apk` 后直接安装即可。APK 为自签名包，安装时会出现系统安全提示；根据机型允许安装未知来源应用后即可正常使用。

## 碎碎念

- 当前主要按个人自用场景持续测试和修复问题，有问题欢迎反馈。
- Wiki 已补充基础使用说明；如果还有想要的功能，欢迎评论留言或提 issue。
- 如果项目对你有帮助，欢迎点个 star。

## 开发和构建说明

以下内容面向需要自行构建、排查打包链路或理解阶段边界的开发者。

## 构建状态说明

- APK Action 徽章会显示 GitHub Actions 当前状态：成功（success）、失败（failure）和进行中（in progress）。
- Runtime Release Tag 徽章固定展示当前 runtime 发布标签：`tavern-runtime-linux-arm64`。
- 若后续需要展示固定 runtime 版本号，建议先把版本字段写入可公开访问的 release 元数据，再接入徽章。

## 进阶说明

## 当前项目包含

1. `android-tavern/`
   Android 宿主工程，应用包名为 `com.jm.sillydroid`。
2. `scripts/build-tavern-android-local.sh`
   本地一键入口，顺序执行 rootfs、dependency packs、server source、apk 四个阶段。
3. `scripts/resolve-tavern-build-plan.sh`
   统一解析宿主版本、上游 tag、变更判定、release 命名和 `artifacts/releases/...` 输出路径。
4. `scripts/build-tavern-android-runtime-image.sh`
   生成 Tavern 专用 runtime image 及 metadata。
5. `scripts/sync-android-rootfs.sh`
   生成离线 Linux rootfs、proot 及相关运行时资产。
6. `scripts/build-tavern-dependency-packs.sh`
   单独构建 `node`、`git` 等 dependency pack zip。
7. `scripts/sync-tavern-android-bootstrap.sh`
   下载指定 SillyTavern tag，生成 `server-source.zip`；只包含 Tavern 源码、overlay 和 npm 运行依赖，不包含 dependency packs，也不直接产出最终 server payload。
8. `scripts/build-tavern-android-apk.sh`
   当前是阶段 4 脚本，只消费现有 runtime image、server source、dependency packs 组装 debug 或 release APK，不再作为推荐的一键总入口。
9. `scripts/android-build-common.sh`
   Android/WSL 构建公共环境脚本。

## 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- 当前链路只支持 `linux-arm64`。
- Android SDK/JDK 不要求先手工装齐；构建脚本会按当前实现自动补齐缺失部分。

## 快速开始

建议在 WSL 或 Linux bash 环境里直接执行：

```bash
git clone https://github.com/jialmaster/SillyDroid.git
cd SillyDroid
bash ./scripts/build-tavern-android-local.sh
```

如果需要固定上游版本或构建类型，可以显式传参：

```bash
bash ./scripts/build-tavern-android-local.sh --tavern-tag 1.18.0 --build-type debug
```

## 补充说明

- 默认构建配置见 `sillydroid-build-config.json`。
- 当前本地入口脚本为 `scripts/build-tavern-android-local.sh`。
- 默认 APK 输出目录为 `artifacts/releases/android-apk/`。

## 交流

- QQ交流群：748515688

## 免责声明

- 本项目仅供学习、研究、技术验证与交流使用。
- 使用者应自行确认本项目及相关上游项目、扩展、模型、数据与网络访问行为符合当地法律法规、平台规则及许可证要求。
- 因部署、二次开发、插件安装、数据迁移、第三方服务接入或其他实际使用行为产生的风险与责任，由使用者自行承担。
- 本项目不对任何生产环境、商业用途、合规适配或特定业务结果作明示或默示保证。


