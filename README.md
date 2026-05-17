# SillyDroid Android

[![APK Action](https://img.shields.io/github/actions/workflow/status/jialmaster/SillyDroid/sillydroid-upstream-apk.yml?label=SillyDroid%20APK)](https://github.com/jialmaster/SillyDroid/actions/workflows/sillydroid-upstream-apk.yml)
[![Runtime Release Tag](https://img.shields.io/badge/Runtime%20Release%20Tag-tavern--runtime--linux--arm64-2563eb)](https://github.com/jialmaster/SillyDroid/releases/tag/tavern-runtime-linux-arm64)
[![Latest Release](https://img.shields.io/github/v/release/jialmaster/SillyDroid?display_name=tag&label=SillyDroid%20Version)](https://github.com/jialmaster/SillyDroid/releases/latest)
[![Release Time](https://img.shields.io/github/release-date/jialmaster/SillyDroid?label=SillyDroid%20Release%20Time)](https://github.com/jialmaster/SillyDroid/releases/latest)

SillyDroid 是一个把 Linux 运行环境、SillyTavern 和 WebView 打包进单个 APK 的项目。目标很直接：不用额外安装 Termux，不用自己搭环境，下载安装后就能在 Android 上跑起来。

## 用户使用

### 这是啥

- 单个 APK 内置运行环境、SillyTavern 和 WebView。
- 首次启动会自动解压环境，通常约 1 分钟，具体取决于机型和存储性能。
- 应用内置 WebView，对外部浏览器依赖较低，流式聊天和小窗场景更稳定。
- 可选扩展会在首次启动时提示安装，安装过程需要设备能访问 GitHub。

### 核心功能

- 基础设置：可视化配置 SillyTavern 的常用选项，例如端口、接口等。
- 扩展管理：安装、重新拉取、删除扩展，支持批量删除。
- 数据迁移：导入、导出完整酒馆数据，包括角色卡、对话、配置、插件和扩展。
- 实时日志：直接查看酒馆运行日志，排查问题更直接。
- 宿主联动：一键跳转 APP 原生设置页，支持刷新按钮和下拉刷新开关。
- 新消息通知：酒馆收到新消息后可发送通知栏提醒，部分机型上可能存在不稳定情况。

### 可选扩展

- 酒馆助手
- 小白 X
- 提示词模板

### 安装与首次启动

1. 从 [最新 Release](https://github.com/jialmaster/SillyDroid/releases/latest) 下载 `.apk`。
2. 直接安装 APK。当前为自签名包，系统出现安全提示时，按机型要求允许安装未知来源应用即可。
3. 首次启动后等待运行环境自动解压完成。
4. 根据提示选择是否安装可选扩展，然后进入酒馆使用。

### 从 Termux 迁移数据

如果之前是在 Termux 中运行 SillyTavern，可以按下面步骤迁移：

1. 在 Termux 中执行下面的命令，导出完整数据 ZIP：

```bash
curl -fsSL https://raw.githubusercontent.com/jialmaster/SillyDroid/master/scripts/export-tavern-data.sh | bash
```

2. 如果第一次没有授权共享存储，先执行：

```bash
termux-setup-storage
```

3. 打开 SillyDroid，等待环境解压完成后，在设置页导入上一步生成的 ZIP。
4. 确认导入后，APP 会替换数据并自动重启服务。

迁移内容包含配置、角色卡、对话、插件和扩展配置。

如果在移动端不方便复制命令，可使用这个备用链接：<https://discord.com/channels/1134557553011998840/1502708065579962500/1504167176213626970>

### 下载与文档

- Wiki：<https://github.com/jialmaster/SillyDroid/wiki>
- 最新 APK / 源码仓库：<https://github.com/jialmaster/SillyDroid/releases/latest>

### 说明

- 项目当前大量实现由 AI 协助完成，作者主要按实际使用需求持续整理和迭代。
- 当前主要按个人自用场景持续测试和修复问题。
- 如果项目对你有帮助，欢迎点个 star。

## 开发与构建

README 只保留常用说明。更细的构建语义、阶段边界和脚本职责，以 `scripts/` 下对应脚本开头的 `Build Plan Contract` 或 `Stage Contract` 注释为准。

### 项目定位

- 仓库维护 Android 宿主、离线运行时打包链，以及基于指定 SillyTavern tag 的 APK 构建与发布流程。
- 上游 SillyTavern 源码不会长期作为主工程保存在仓库里。
- 构建时会同步指定 tag，先生成 stage 3 的 `server-source.zip`，再由 stage 4 组合宿主使用的最终 server payload。

### 四阶段边界

| 阶段 | 负责内容 | 不允许做的事 |
| --- | --- | --- |
| Stage 1 | 构建 runtime image 和 rootfs | 不得构建 dependency packs、server source、server payload、APK |
| Stage 2 | 构建 `node`、`git` 等 dependency packs | 不得构建 runtime image、server source、server payload、APK |
| Stage 3 | 下载并整理指定版本的 Tavern server source | 不得引入 dependency packs，不得生成最终 server payload，不得组装 APK |
| Stage 4 | 组合最终 server payload 并组装 APK | 不得隐式回补 stage 1、2、3 |

补充约束：

- `scripts/build-tavern-android-local.sh` 只负责按顺序编排四个阶段，不允许偷偷塞额外构建逻辑导致本地与 CI 语义分叉。
- 如果后续需要调整阶段边界，必须同时修改对应脚本头部契约和本 README 的“四阶段边界”说明。

### 构建状态说明

- `APK Action` 徽章显示当前 GitHub Actions 的 APK 构建状态。
- `Runtime Release Tag` 徽章固定展示当前 runtime 发布标签：`tavern-runtime-linux-arm64`。
- `Latest Release` 和 `Release Time` 徽章展示当前发布版本及时间。

### 项目结构

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
   单独构建 `node`、`git` 等 dependency pack ZIP。
7. `scripts/sync-tavern-android-bootstrap.sh`
   下载指定 SillyTavern tag，生成 `server-source.zip`；只包含 Tavern 源码、overlay 和 npm 运行依赖，不包含 dependency packs，也不直接产出最终 server payload。
8. `scripts/build-tavern-android-apk.sh`
   阶段 4 脚本，只消费现有 runtime image、server source、dependency packs 组装 debug 或 release APK。
9. `scripts/android-build-common.sh`
   Android / WSL 构建公共环境脚本。

### 前置要求

- Windows + WSL，或直接 Linux bash 环境。
- `bash`、`curl`、`unzip`、`tar`、`sha256sum`、`realpath`。
- 当前链路只支持 `linux-arm64`。
- Android SDK / JDK 不要求先手工装齐；构建脚本会按当前实现自动补齐缺失部分。

### 快速开始

建议在 WSL 或 Linux bash 环境中执行：

```bash
git clone https://github.com/jialmaster/SillyDroid.git
cd SillyDroid
bash ./scripts/build-tavern-android-local.sh
```

如果需要固定上游版本或构建类型，可以显式传参：

```bash
bash ./scripts/build-tavern-android-local.sh --tavern-tag 1.18.0 --build-type debug
```

### 构建补充说明

- 默认构建配置见 `sillydroid-build-config.json`。
- 默认 APK 输出目录为 `artifacts/releases/android-apk/`。

## 数据与扩展详细规则

### 独立扩展安装

- 宿主设置页提供独立的扩展安装入口，不依赖先进入酒馆 Web UI。
- 可直接输入 GitHub、GitLab 或支持 `git clone` 的镜像仓库地址，由宿主在本地运行时里完成校验、预览和安装。
- 已安装扩展支持按 `manifest.homePage` 重新拉取，也支持直接删除。
- 这条链路面向宿主自己的 `extensions` 持久目录，更新 APK 不会直接覆盖用户已安装的第三方扩展。

### 导入识别规则

- 设置页导入会自动识别上游 Tavern 用户备份 ZIP，以及两种完整数据 ZIP。
- Docker 风格完整数据包要求根目录为 `config`、`data`、`plugins`、`extensions`。
- Linux / Termux 风格完整数据包要求根目录为 `config`、`data`、`plugins`、`public`，其中 `public/scripts/extensions/third-party` 会被识别为第三方扩展目录导入。
- 导入前会先做类型识别和确认，不会在未确认的情况下直接覆盖现有数据。

### Termux 导出脚本说明

- 仓库内提供了 [scripts/export-tavern-data.sh](scripts/export-tavern-data.sh)，用于在官方 Termux 版 SillyTavern 环境里一键导出数据包。
- 脚本导出的是 Linux / Termux public 结构 ZIP：根目录为 `config`、`data`、`plugins`、`public`。
- 第三方扩展目录位于 `public/scripts/extensions/third-party`。
- 脚本不会把程序本体、`node_modules`、源码或其他运行时文件打进 ZIP。
- 默认会自动探测官方 Termux 安装目录，通常是 `~/SillyTavern`；如果机器上同时存在源码目录和实际运行目录，建议显式传 `--install-root`。
- 不需要先 clone 这个仓库；直接下载脚本执行即可。若缺少 `zip`，脚本会自动尝试执行 `pkg install -y zip`。

如需手工指定安装目录或输出目录，可执行：

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/jialmaster/SillyDroid/master/scripts/export-tavern-data.sh) \
  --install-root "$HOME/SillyTavern" \
  --output-dir "$HOME/storage/shared/Download"
```

### 扩展数据边界

- 会被备份和导入的是持久数据目录中的扩展内容，也就是宿主当前使用的 `extensions` 持久目录。
- 对于 Linux / Termux 风格数据包，若扩展位于 `public/scripts/extensions/third-party`，导入时会自动映射到宿主的 `extensions` 持久目录。
- 扩展自己生成的用户数据如果写在 `data` 目录下，也会随 `data` 一起备份和导入。
- 不会被备份和导入的是程序本体自带的 server 文件、`public` 里的其他静态资源、APK 内置 `bundled-extensions` 资产，以及重新安装 APK 后可再生成的非持久运行时文件。

## 免责声明

- 本项目仅供学习、研究、技术验证与交流使用。
- 使用者应自行确认本项目及相关上游项目、扩展、模型、数据与网络访问行为符合当地法律法规、平台规则及许可证要求。
- 因部署、二次开发、插件安装、数据迁移、第三方服务接入或其他实际使用行为产生的风险与责任，由使用者自行承担。
- 本项目不对任何生产环境、商业用途、合规适配或特定业务结果作明示或默示保证。
